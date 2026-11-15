package com.cue.offlinebrain.notifications

import android.content.pm.ApplicationInfo
import android.app.Notification
import java.util.Locale

/** Rule-based notification grouping; no text is sent off-device. */
object NotificationCategory {
    val labels: List<String> = listOf(
        "Calls", "Messages/SMS", "Chat", "Social", "Email", "Music and media",
        "Weather", "Shopping", "Food", "Games", "Finance", "System", "Other"
    )

    private val packages = mapOf(
        "com.whatsapp" to "Chat", "org.telegram.messenger" to "Chat", "org.thoughtcrime.securesms" to "Chat",
        "com.viber.voip" to "Chat", "com.discord" to "Chat", "com.google.android.apps.messaging" to "Messages/SMS",
        "com.android.mms" to "Messages/SMS", "com.samsung.android.messaging" to "Messages/SMS",
        "com.android.messaging" to "Messages/SMS", "com.google.android.dialer" to "Calls",
        "com.android.dialer" to "Calls", "com.android.incallui" to "Calls",
        "com.android.server.telecom" to "Calls", "com.android.phone" to "Calls", "com.spotify.music" to "Music and media",
        "com.google.android.youtube" to "Music and media", "com.google.android.apps.youtube.music" to "Music and media",
        "com.soundcloud.android" to "Music and media", "com.amazon.mShop.android.shopping" to "Shopping",
        "com.flipkart.android" to "Shopping", "com.myntra.android" to "Shopping", "com.meesho.supply" to "Shopping",
        "in.swiggy.android" to "Food", "com.application.zomato" to "Food",
        "com.ubercab.eats" to "Food", "com.google.android.gm" to "Email",
        "com.microsoft.office.outlook" to "Email", "com.facebook.katana" to "Social",
        "com.instagram.android" to "Social", "com.linkedin.android" to "Social", "com.twitter.android" to "Social",
        "com.snapchat.android" to "Social", "com.reddit.frontpage" to "Social", "com.sgiggle.production" to "Social", "com.google.android.apps.weather" to "Weather",
        "com.accuweather.android" to "Weather", "com.phonepe.app" to "Finance", "net.one97.paytm" to "Finance",
        "com.google.android.apps.nbu.paisa.user" to "Finance", "com.google.android.apps.walletnfcrel" to "Finance"
    )

    fun infer(packageName: String, notificationCategory: String?, applicationCategory: Int, hasMediaSession: Boolean = false): String {
        val pkg = packageName.lowercase(Locale.ROOT)
        if (pkg.startsWith("com.xiaomi.") || pkg.startsWith("com.miui.")) return "System"
        if (pkg == "com.android.server.telecom" || pkg == "com.android.phone") return "Calls"
        packages[pkg]?.let { mapped ->
            // A phone call notification from a chat app should still be shown as a call.
            if (notificationCategory == Notification.CATEGORY_CALL) return "Calls"
            return mapped
        }
        if (notificationCategory == Notification.CATEGORY_CALL) return "Calls"
        if (notificationCategory == Notification.CATEGORY_EMAIL) return "Email"
        if (notificationCategory == Notification.CATEGORY_TRANSPORT) return if (hasMediaSession) "Music and media" else "Other"
        if (notificationCategory == Notification.CATEGORY_SOCIAL) return "Social"
        if (notificationCategory == Notification.CATEGORY_MESSAGE) return "Messages/SMS"
        if (applicationCategory == ApplicationInfo.CATEGORY_GAME) return "Games"
        if (applicationCategory == ApplicationInfo.CATEGORY_SOCIAL) return "Social"
        if (applicationCategory == ApplicationInfo.CATEGORY_NEWS) return "Other"
        if (applicationCategory == ApplicationInfo.CATEGORY_AUDIO || applicationCategory == ApplicationInfo.CATEGORY_VIDEO) return "Music and media"
        if (pkg == "android" || pkg.startsWith("com.android.") || notificationCategory == Notification.CATEGORY_SYSTEM) return "System"
        return "Other"
    }

    fun normalized(value: String): String = when (value.trim()) {
        "Messages (SMS)" -> "Messages/SMS"
        "Food and delivery" -> "Food"
        else -> value.trim().takeIf { it in labels } ?: "Other"
    }
}

/** A bounded in-process guard complements the database's unique (notification key, text hash) index. */
class NotificationBurstGuard(private val windowMillis: Long = 1_000L) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun accept(key: String, textHash: String, now: Long): Boolean {
        val composite = "$key\u0000$textHash"
        val previous = seen[composite]
        if (previous != null && now - previous < windowMillis) return false
        seen[composite] = now
        val cutoff = now - windowMillis
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value < cutoff) iterator.remove()
        while (seen.size > 512) seen.remove(seen.entries.first().key)
        return true
    }
}

/** Keep call/SMS body text out of default notification summaries. */
object NotificationPrivacyFormatter {
    private val otpPattern = Regex("(?i)\\b(otp|one[- ]time (?:password|code)|verification code|security code|passcode|code)\\b([^\\d]{0,20})(\\d{4,8})\\b")

    fun line(category: String, sender: String, body: String, includeMessageBody: Boolean = false): String = when (category) {
        "Calls" -> sender.ifBlank { "Caller not identified" }
        "Messages/SMS", "Messages (SMS)" -> if (includeMessageBody) "$sender: $body".trim(':', ' ') else sender.ifBlank { "Sender not identified" }
        "Chat" -> listOf(sender, body).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "Chat message" }
        else -> listOf(sender, body).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "Notification" }
    }

    fun containsOtpCode(text: String): Boolean = otpPattern.containsMatchIn(text)

    fun maskOtpCodes(text: String): String = otpPattern.replace(text) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}digits hidden"
    }
}
