package com.cue.offlinebrain.notifications

import android.app.Notification
import android.os.Bundle
import com.cue.offlinebrain.diagnostics.CrashLog

/** Small, independently guarded extraction of the optional fields Android may put in a notification. */
data class ExtractedNotificationText(
    val title: String = "",
    val text: String = "",
    val subText: String = "",
    val sender: String = "",
    val groupName: String = ""
)

object NotificationTextExtractor {
    const val TITLE_LIMIT = 500
    const val TEXT_LIMIT = 3_000
    const val SENDER_LIMIT = 180
    const val KEY_LIMIT = 250

    /** Truncate without indexing beyond the input and avoid leaving half of a UTF-16 surrogate pair. */
    fun safeTake(value: String?, limit: Int): String {
        if (value.isNullOrEmpty() || limit <= 0) return ""
        val cut = value.take(limit)
        return if (cut.lastOrNull()?.isHighSurrogate() == true && cut.length < value.length && value.drop(cut.length).firstOrNull()?.isLowSurrogate() == true) {
            cut.dropLast(1)
        } else cut
    }

    fun extract(extras: Bundle?, fallbackGroup: String = ""): ExtractedNotificationText {
        if (extras == null) return ExtractedNotificationText(groupName = safeTake(fallbackGroup, SENDER_LIMIT))
        val title = readText(extras, Notification.EXTRA_TITLE)
        val regularText = readText(extras, Notification.EXTRA_TEXT)
        val bigText = readText(extras, Notification.EXTRA_BIG_TEXT)
        val subText = readText(extras, Notification.EXTRA_SUB_TEXT)
        val inboxText = readInboxText(extras)
        val messaging = readMessaging(extras)
        val sender = messaging?.first.orEmpty()
        val chosenText = sequenceOf(bigText, regularText, inboxText, messaging?.second.orEmpty())
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val conversationTitle = readText(extras, "android.conversationTitle")
        val groupName = sequenceOf(conversationTitle, subText, fallbackGroup)
            .firstOrNull { it.isNotBlank() }.orEmpty()
        return ExtractedNotificationText(
            title = safeTake(title, TITLE_LIMIT),
            text = safeTake(chosenText, TEXT_LIMIT),
            subText = safeTake(subText, SENDER_LIMIT),
            sender = safeTake(sender, SENDER_LIMIT),
            groupName = safeTake(groupName, SENDER_LIMIT)
        )
    }

    private fun readText(extras: Bundle, key: String): String = try {
        safeTake(extras.getCharSequence(key)?.toString(), TEXT_LIMIT)
    } catch (error: Exception) {
        CrashLog.record("NOTIFICATIONS", "could not read notification field '$key'", error)
        ""
    }

    private fun readInboxText(extras: Bundle): String = try {
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.asSequence()
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.joinToString(" · ")
            .orEmpty()
    } catch (error: Exception) {
        CrashLog.record("NOTIFICATIONS", "could not read inbox-style notification lines", error)
        ""
    }

    private fun readMessaging(extras: Bundle): Pair<String, String>? = try {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val latest = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)
            ?.lastOrNull() ?: return null
        val sender = latest.senderPerson?.name?.toString()
            ?: latest.sender?.toString()
            ?: ""
        val body = latest.text?.toString().orEmpty()
        sender to body
    } catch (error: Exception) {
        CrashLog.record("NOTIFICATIONS", "could not read messaging-style notification", error)
        null
    }
}
