package com.cue.offlinebrain.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.NotificationEntity
import com.cue.offlinebrain.data.BrainReply
import com.cue.offlinebrain.data.BrainReplyCodec
import com.cue.offlinebrain.data.MessageEntity
import com.cue.offlinebrain.data.ReplyCategoryCount
import com.cue.offlinebrain.data.ReplyNotificationItem
import com.cue.offlinebrain.data.shouldStoreNotification
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stable, non-content reasons used in diagnostic counters and the last-decision feed. */
enum class NotificationDecisionReason(val code: String) {
    SAVED("SAVED"),
    BLOCKED_APP("blocked-app"),
    NOT_IN_ALLOW_LIST("not-in-allow-list"),
    ONGOING("ongoing"),
    GROUP_SUMMARY("group-summary"),
    DUPLICATE("duplicate"),
    EMPTY_TEXT("empty-text"),
    RATE_LIMITED("rate-limited"),
    CAPTURE_OFF("capture-off"),
    OWN_PACKAGE("own-package"),
    EXCEPTION("exception"),
    NULL_SBN("null-sbn"),
    MISSING_NOTIFICATION("missing-notification")
}

data class NotificationPipelineDecision(
    val packageName: String,
    val reason: NotificationDecisionReason,
    val category: String,
    val rawCategory: String,
    val flags: Int,
    val source: String,
    val atMillis: Long = System.currentTimeMillis()
) {
    val saved: Boolean get() = reason == NotificationDecisionReason.SAVED
    val summary: String get() = if (saved) "SAVED" else "SKIPPED:${reason.code}"
}

data class NotificationDecisionLine(val packageName: String, val reason: String, val saved: Boolean, val atMillis: Long)
data class NotificationPipelineSnapshot(
    val received: Int = 0,
    val saved: Int = 0,
    val removed: Int = 0,
    val skippedByReason: Map<String, Int> = emptyMap(),
    val recent: List<NotificationDecisionLine> = emptyList(),
    val lastSavedAt: Long = 0L,
    val lastCallbackAt: Long = 0L,
    val lastRemovedAt: Long = 0L
)

/** Process-local counters; the feed includes no title, body, sender, or message text. */
object NotificationPipelineTelemetry {
    private val _snapshot = MutableStateFlow(NotificationPipelineSnapshot())
    private val skipAggregator = NotificationDiagnosticAggregator()
    val snapshot: StateFlow<NotificationPipelineSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun callbackReceived(packageName: String, category: String, rawCategory: String, flags: Int, source: String) {
        val old = _snapshot.value
        val at = System.currentTimeMillis()
        _snapshot.value = old.copy(received = (old.received + 1).coerceAtMost(Int.MAX_VALUE), lastCallbackAt = at)
        // Count every ingress synchronously; the final decision is logged or aggregated below.
        // Avoid one disk line per media/ongoing update because music players may post continuously.
    }

    @Synchronized
    fun callbackRemoved(packageName: String, source: String) {
        val old = _snapshot.value
        val at = System.currentTimeMillis()
        _snapshot.value = old.copy(removed = (old.removed + 1).coerceAtMost(Int.MAX_VALUE), lastRemovedAt = at)
        try { CrashLog.record("NOTIFICATIONS", "callback removed source=${safeLogField(source)} package=${safeLogField(packageName)} outcome=removed") }
        catch (_: Exception) { /* keep system callback safe */ }
    }

    @Synchronized
    fun decided(decision: NotificationPipelineDecision) {
        val old = _snapshot.value
        val saved = decision.saved
        val skips = if (saved) old.skippedByReason else {
            val reason = decision.reason.code
            old.skippedByReason + (reason to ((old.skippedByReason[reason] ?: 0) + 1))
        }
        val line = NotificationDecisionLine(
            packageName = safeLogField(decision.packageName),
            reason = if (saved) "SAVED" else decision.reason.code,
            saved = saved,
            atMillis = decision.atMillis
        )
        _snapshot.value = old.copy(
            saved = if (saved) old.saved + 1 else old.saved,
            skippedByReason = skips,
            recent = (old.recent + line).takeLast(20),
            lastSavedAt = if (saved) decision.atMillis else old.lastSavedAt
        )
        val noisyUpdate = (decision.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            decision.rawCategory.equals(Notification.CATEGORY_TRANSPORT, ignoreCase = true) ||
            decision.category == "Music and media"
        val expired = if (saved) emptyList() else skipAggregator.record(decision.packageName, decision.reason, decision.atMillis, noisyUpdate)
        try {
            expired.forEach { CrashLog.record("NOTIFICATIONS", it.asLine()) }
            if (saved && !noisyUpdate) {
                CrashLog.record("NOTIFICATIONS", "notification-saved package=${safeLogField(decision.packageName)} category=${safeLogField(decision.category)}")
            }
        } catch (_: Exception) {
            // Diagnostics must not turn a notification callback into a crash if log storage is unavailable.
        }
    }

    @Synchronized
    fun flushAggregatedSkips() {
        try { skipAggregator.drain().forEach { CrashLog.record("NOTIFICATIONS", it.asLine()) } }
        catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "could not flush notification skip summaries", error) }
    }

    @Synchronized
    fun resetForTests() { _snapshot.value = NotificationPipelineSnapshot(); skipAggregator.drain() }

    private fun safeLogField(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(180)
}

/** Pure policy: a group summary is skipped only when an active child was observed. */
object NotificationCapturePolicy {
    fun rejection(
        settings: AppSettings,
        packageName: String,
        ownPackageName: String,
        title: String,
        text: String,
        isGroupSummary: Boolean,
        activeGroupChildExists: Boolean,
        isOngoing: Boolean,
        isTestCapture: Boolean,
        ongoingRecentlySaved: Boolean,
        burstDuplicate: Boolean
    ): NotificationDecisionReason? {
        if (!settings.notificationLiveCaptureEnabled) return NotificationDecisionReason.CAPTURE_OFF
        if (!isTestCapture && packageName.equals(ownPackageName, ignoreCase = true)) return NotificationDecisionReason.OWN_PACKAGE
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        val blocked = settings.notificationBlocklist.lines().map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank)
        if (blocked.any { pkg == it || pkg.startsWith("$it.") }) return NotificationDecisionReason.BLOCKED_APP
        val allowed = settings.notificationAllowlist.lines().map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank)
        if (allowed.isNotEmpty() && allowed.none { pkg == it || pkg.startsWith("$it.") })
            return NotificationDecisionReason.NOT_IN_ALLOW_LIST
        if (isGroupSummary && activeGroupChildExists) return NotificationDecisionReason.GROUP_SUMMARY
        if (title.isBlank() && text.isBlank() && !isGroupSummary) return NotificationDecisionReason.EMPTY_TEXT
        if (isOngoing && ongoingRecentlySaved) return NotificationDecisionReason.ONGOING
        if (burstDuplicate) return NotificationDecisionReason.RATE_LIMITED
        return null
    }

    fun reasonForStoreResult(inserted: Boolean): NotificationDecisionReason =
        if (inserted) NotificationDecisionReason.SAVED else NotificationDecisionReason.DUPLICATE
}

/** Rate limit measured from the last successful save, never from a skipped update. */
class OngoingNotificationRateLimiter(private val windowMillis: Long = 30_000L) {
    private val savedAt = LinkedHashMap<String, Long>()

    @Synchronized
    fun shouldSkip(key: String, nowElapsed: Long): Boolean {
        val previous = savedAt[key] ?: return false
        return nowElapsed >= previous && nowElapsed - previous < windowMillis
    }

    @Synchronized
    fun markSaved(key: String, nowElapsed: Long) {
        savedAt[key] = nowElapsed
        val cutoff = nowElapsed - windowMillis * 4
        val iterator = savedAt.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value < cutoff) iterator.remove()
        while (savedAt.size > 512) savedAt.remove(savedAt.entries.firstOrNull()?.key ?: break)
    }

    @Synchronized
    fun remove(key: String) { savedAt.remove(key) }
}

private object ActiveNotificationGroups {
    private val childGroups = LinkedHashMap<String, String>()
    private val lock = Any()

    fun observeChild(packageName: String, postTime: Long, groupKey: String) = synchronized(lock) {
        val group = groupKey.trim()
        if (group.isBlank()) return@synchronized
        childGroups["$packageName:$postTime"] = group
        while (childGroups.size > 512) childGroups.remove(childGroups.keys.firstOrNull() ?: break)
    }

    fun hasChild(groupKey: String): Boolean = synchronized(lock) { childGroups.values.any { it == groupKey } }

    fun remove(packageName: String, postTime: Long) = synchronized(lock) { childGroups.remove("$packageName:$postTime") }

    fun reset() = synchronized(lock) { childGroups.clear() }
}

private object NotificationRuntimeGuards {
    val burst = NotificationBurstGuard()
    val ongoing = OngoingNotificationRateLimiter()
    private val processMutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = processMutex.withLock { block() }
}

/** Shared processor called by real listener callbacks and by Diagnostics synthetic SBNs. */
object NotificationCapturePipeline {
    /** Synchronous receipt mark for the real Service callback, before any coroutine can be cancelled. */
    fun noteCallbackIngress(sbn: StatusBarNotification?, source: String = "callback") {
        var packageName = "unknown"
        var category = "unknown"
        var rawCategory = "unknown"
        var flags = 0
        try { packageName = sbn?.packageName?.takeIf(String::isNotBlank) ?: "unknown" }
        catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "callback package metadata unavailable", error) }
        try {
            val notification = sbn?.notification
            flags = notification?.flags ?: 0
            rawCategory = notification?.category.orEmpty().ifBlank { "none" }
            category = NotificationCategory.infer(packageName, rawCategory, android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED)
        } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "callback category or flags unavailable package=${safeField(packageName)}", error) }
        NotificationPipelineTelemetry.callbackReceived(packageName, category, rawCategory, flags, source)
    }

    suspend fun processNotification(
        context: Context,
        sbn: StatusBarNotification?,
        source: String = "callback",
        receiptAlreadyRecorded: Boolean = false
    ): NotificationPipelineDecision = withContext(Dispatchers.IO) {
        if (!receiptAlreadyRecorded) noteCallbackIngress(sbn, source)
        NotificationRuntimeGuards.serialized {
            var pkg = ""
            var rawCategory = "unknown"
            var resolvedCategory = "Other"
            var flags = 0
            var testToken: String? = null
            try {
                if (sbn == null) return@serialized record(
                    NotificationPipelineDecision("unknown", NotificationDecisionReason.NULL_SBN, "unknown", "unknown", 0, source)
                )
                pkg = try { sbn.packageName.takeIf(String::isNotBlank).orEmpty() }
                catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "notification package could not be read", error)
                    return@serialized record(NotificationPipelineDecision("unknown", NotificationDecisionReason.EXCEPTION, "unknown", "unknown", 0, source))
                }
                val notification = try { sbn.notification } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "notification object read failed package=${safeField(pkg)}", error)
                    null
                }
                if (notification == null) return@serialized record(
                    NotificationPipelineDecision(pkg, NotificationDecisionReason.MISSING_NOTIFICATION, "unknown", "unknown", 0, source)
                )
                flags = notification.flags
                rawCategory = notification.category.orEmpty().ifBlank { "none" }
                val testCandidate = readTestToken(notification)
                testToken = testCandidate?.takeIf(NotificationTestCapture::isPending)
                val isTestCapture = testToken != null
                val extras = try { notification.extras } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "notification extras unavailable package=${safeField(pkg)}", error)
                    null
                }
                val extracted = NotificationTextExtractor.extract(extras, notification.group.orEmpty())
                val groupKey = safeField(sbn.groupKey.orEmpty())
                val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
                if (!isGroupSummary && groupKey.isNotBlank()) ActiveNotificationGroups.observeChild(pkg, sbn.postTime, groupKey)
                val activeChild = isGroupSummary && groupKey.isNotBlank() && ActiveNotificationGroups.hasChild(groupKey)
                val title = extracted.sender.takeIf { it.isNotBlank() } ?: extracted.title
                var body = extracted.text
                if (isGroupSummary && title.isBlank() && body.isBlank()) {
                    // Keep a lone, unreadable summary visible as a metadata-only row rather than dropping it.
                    body = extracted.groupName.takeIf(String::isNotBlank)?.let { "$it notifications" } ?: "Notification group summary"
                }
                val app = context.applicationContext as? OfflineBrainApplication
                    ?: throw IllegalStateException("The app database and settings are unavailable")
                val settings = app.settings.settings.first()
                val appCategory = try { app.packageManager.getApplicationInfo(pkg, 0).category }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    PackageLabelResolver.label(context, pkg)
                    android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
                } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "source app category unavailable package=${safeField(pkg)}", error)
                    android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
                }
                val hasMediaMetadata = try {
                    extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
                        extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle", ignoreCase = true)
                } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "media metadata check failed package=${safeField(pkg)}", error)
                    false
                }
                val ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 || hasMediaMetadata
                val baseCategory = NotificationCategory.infer(pkg, rawCategory, appCategory, hasMediaSession = hasMediaMetadata)
                val override = settings.notificationCategoryOverrides.lines().firstNotNullOfOrNull { line ->
                    val split = line.indexOf('=')
                    if (split > 0 && line.take(split).equals(pkg, ignoreCase = true))
                        NotificationCategory.normalized(line.drop(split + 1).trim()) else null
                }
                resolvedCategory = override ?: baseCategory
                val trackSignature = listOf(extracted.title, extracted.subText, extracted.groupName).joinToString("\u0000")
                    .trim('\u0000').ifBlank { title }
                val mediaTrack = baseCategory == "Music and media" && (hasMediaMetadata || ongoing)
                val key = NotificationTextExtractor.safeTake(
                    if (mediaTrack) "$pkg:media:${sha256(trackSignature).take(24)}"
                    else sbn.key?.takeIf(String::isNotBlank) ?: "$pkg:${sbn.id}:${sbn.tag.orEmpty()}",
                    NotificationTextExtractor.KEY_LIMIT
                )
                val limiterNow = SystemClock.elapsedRealtime()
                val ongoingRecentlySaved = ongoing && NotificationRuntimeGuards.ongoing.shouldSkip(key, limiterNow)
                val textHash = if (mediaTrack) sha256(trackSignature) else sha256("$title\u0000$body\u0000${sbn.postTime}")
                val rejection = NotificationCapturePolicy.rejection(
                    settings = settings,
                    packageName = pkg,
                    ownPackageName = app.packageName,
                    title = title,
                    text = body,
                    isGroupSummary = isGroupSummary,
                    activeGroupChildExists = activeChild,
                    isOngoing = ongoing,
                    isTestCapture = isTestCapture,
                    ongoingRecentlySaved = ongoingRecentlySaved,
                    burstDuplicate = false
                )
                if (rejection != null) return@serialized record(
                    NotificationPipelineDecision(pkg, rejection, resolvedCategory, rawCategory, flags, source)
                )
                if (!NotificationRuntimeGuards.burst.accept(key, textHash, limiterNow)) return@serialized record(
                    NotificationPipelineDecision(pkg, NotificationDecisionReason.RATE_LIMITED, resolvedCategory, rawCategory, flags, source)
                )
                val action = notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true && it.actionIntent != null }
                val groupName = extracted.groupName.ifBlank { if (isGroupSummary) sbn.groupKey.orEmpty() else "" }
                val insertedId = NotificationSavePath.save(
                    app.database.dao(),
                    NotificationEntity(
                        packageName = pkg,
                        title = NotificationTextExtractor.safeTake(title, NotificationTextExtractor.TITLE_LIMIT),
                        text = NotificationTextExtractor.safeTake(body, NotificationTextExtractor.TEXT_LIMIT),
                        postedAt = sbn.postTime,
                        dedupeKey = key,
                        textHash = textHash,
                        category = resolvedCategory,
                        isOngoing = ongoing,
                        groupName = NotificationTextExtractor.safeTake(groupName, NotificationTextExtractor.SENDER_LIMIT)
                    ),
                    settings.notificationRetentionDays
                )
                val inserted = insertedId > 0L
                val reason = NotificationCapturePolicy.reasonForStoreResult(inserted)
                if (inserted) {
                    if (ongoing) NotificationRuntimeGuards.ongoing.markSaved(key, limiterNow)
                    context.getSharedPreferences("notification_capture_state", Context.MODE_PRIVATE).edit()
                        .putLong("last_saved_at", System.currentTimeMillis()).apply()
                    NotificationReplyRegistry.register(pkg, sbn.postTime, action?.actionIntent, action?.remoteInputs)
                    if (!isTestCapture) {
                        val mutedApp = settings.notificationMutedPackages.lines().any { line ->
                            val muted = line.trim().lowercase(Locale.ROOT)
                            muted.isNotBlank() && (pkg.lowercase(Locale.ROOT) == muted || pkg.lowercase(Locale.ROOT).startsWith("$muted."))
                        }
                        val mutedCategory = settings.notificationMutedCategories.lines().any { it.trim().equals(resolvedCategory, true) }
                        if (NotificationAlertPolicy.shouldPublish(mutedApp, mutedCategory, ongoing, hasMediaMetadata)) {
                            if (settings.notificationInAppAlertsEnabled || settings.notificationSpeakNewEnabled) {
                                NotificationAlertQueue.enqueue(NotificationAlert(
                                    id = "$pkg:${sbn.postTime}", packageName = pkg, title = title, text = body,
                                    category = resolvedCategory, groupName = groupName, postedAt = sbn.postTime
                                ))
                            }
                            if (settings.notificationChatAlertsEnabled) {
                                val appLabel = PackageLabelResolver.label(context, pkg)
                                val reply = BrainReply.NotificationList(
                                    title = "New: $appLabel — ${title.ifBlank { "Notification" }}", category = resolvedCategory,
                                    totalCount = 1, unreadCount = 1, latestAt = sbn.postTime,
                                    categoryCounts = listOf(ReplyCategoryCount(resolvedCategory, 1)),
                                    items = listOf(ReplyNotificationItem(
                                        id = insertedId, packageName = pkg, title = title.ifBlank { appLabel }, text = body,
                                        category = resolvedCategory, postedAt = sbn.postTime, groupName = groupName,
                                        isUnread = true
                                    )),
                                    note = "New item captured just now."
                                )
                                app.database.dao().addMessage(MessageEntity(
                                    role = "assistant", text = reply.plainText, replyJson = BrainReplyCodec.encode(reply)
                                ))
                            }
                        }
                    }
                } else NotificationReplyRegistry.remove(pkg, sbn.postTime)
                record(NotificationPipelineDecision(pkg, reason, resolvedCategory, rawCategory, flags, source), testToken, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "notification processor failed package=${safeField(pkg)}", error)
                record(NotificationPipelineDecision(pkg.ifBlank { "unknown" }, NotificationDecisionReason.EXCEPTION, resolvedCategory, rawCategory, flags, source), testToken)
            }
        }
    }

    fun removeNotification(packageName: String, postTime: Long, notificationKey: String?, source: String = "removed") {
        NotificationPipelineTelemetry.callbackRemoved(packageName.ifBlank { "unknown" }, source)
        ActiveNotificationGroups.remove(packageName, postTime)
        NotificationRuntimeGuards.ongoing.remove(notificationKey?.takeIf(String::isNotBlank) ?: "$packageName:$postTime")
        NotificationReplyRegistry.remove(packageName, postTime)
    }

    /** Completes tracing when the Android callback could not even schedule the shared processor. */
    fun recordCallbackDispatchFailure(sbn: StatusBarNotification?, source: String, error: Exception) {
        var pkg = "unknown"
        var rawCategory = "unknown"
        var category = "unknown"
        var flags = 0
        try { pkg = sbn?.packageName?.takeIf(String::isNotBlank) ?: "unknown" }
        catch (metadataError: Exception) { CrashLog.record("NOTIFICATIONS", "failed callback package metadata unavailable", metadataError) }
        try {
            val notification = sbn?.notification
            flags = notification?.flags ?: 0
            rawCategory = notification?.category.orEmpty().ifBlank { "none" }
            category = NotificationCategory.infer(pkg, rawCategory, android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED)
        } catch (metadataError: Exception) { CrashLog.record("NOTIFICATIONS", "failed callback category/flags unavailable", metadataError) }
        CrashLog.record("NOTIFICATIONS", "posted-notification dispatch failed package=${safeField(pkg)}", error)
        NotificationPipelineTelemetry.decided(
            NotificationPipelineDecision(pkg, NotificationDecisionReason.EXCEPTION, category, rawCategory, flags, source)
        )
    }

    fun resetActiveGroups() = ActiveNotificationGroups.reset()

    private fun record(decision: NotificationPipelineDecision, token: String? = null, dedupeKey: String = ""): NotificationPipelineDecision {
        NotificationPipelineTelemetry.decided(decision)
        token?.let { NotificationTestCapture.complete(it, decision, dedupeKey) }
        return decision
    }

    private fun readTestToken(notification: Notification): String? = try {
        notification.extras?.getString(NotificationTestCapture.EXTRA_TOKEN)?.takeIf { it.length <= 80 }
    } catch (error: Exception) {
        CrashLog.record("NOTIFICATIONS", "test marker read failed", error)
        null
    }

    private fun safeField(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(250)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Posts a benign app-owned notification and waits for the real NotificationListener callback. */
object NotificationTestCapture {
    internal const val EXTRA_TOKEN = "com.cue.offlinebrain.extra.NOTIFICATION_TEST_TOKEN"
    private const val CHANNEL_ID = "notification_capture_test"
    private const val NOTIFICATION_ID = 47031
    private const val NOTIFICATION_TAG = "cue-notification-capture-test"
    private data class CompletedCapture(val decision: NotificationPipelineDecision, val dedupeKey: String)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<CompletedCapture>>()

    data class Result(val decision: NotificationPipelineDecision?, val error: String? = null) {
        val saved: Boolean get() = decision?.saved == true
    }

    internal fun isPending(token: String?): Boolean = token != null && pending.containsKey(token)
    internal fun complete(token: String, decision: NotificationPipelineDecision, dedupeKey: String = "") {
        pending[token]?.complete(CompletedCapture(decision, dedupeKey))
    }

    suspend fun postAndWait(context: Context, timeoutMillis: Long = 10_000L): Result = withContext(Dispatchers.Main.immediate) {
        val app = context.applicationContext
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<CompletedCapture>()
        pending[token] = result
        try {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                ?: return@withContext Result(null, "Android notification service is unavailable.")
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture test", android.app.NotificationManager.IMPORTANCE_LOW))
            }
            val extras = Bundle().apply { putString(EXTRA_TOKEN, token) }
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Offline Brain test capture")
                .setContentText("Test item only; it contains no personal message.")
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .addExtras(extras)
                .build()
            try {
                NotificationManagerCompat.from(app).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
            } catch (security: SecurityException) {
                CrashLog.record("NOTIFICATIONS", "test notification post was blocked by Android", security)
                return@withContext Result(null, "Android blocked the test notification. Check the app's notification permission and channel settings.")
            }
            val completed = withTimeoutOrNull(timeoutMillis) { result.await() }
            if (completed == null) Result(null, "No listener decision arrived within ${timeoutMillis / 1000L} seconds.") else {
                val cleaned = if (completed.decision.saved && completed.dedupeKey.isNotBlank()) try {
                    val appModel = app as? OfflineBrainApplication
                    if (appModel == null) false else withContext(Dispatchers.IO) {
                        appModel.database.dao().deleteNotificationByDedupeKey(completed.dedupeKey)
                        true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "test capture temporary-row cleanup failed", error)
                    false
                } else false
                Result(completed.decision, if (cleaned) null else if (completed.decision.saved) "Saved test row could not be removed." else null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CrashLog.record("NOTIFICATIONS", "test capture post/wait failed", error)
            Result(null, error.message ?: error.javaClass.simpleName)
        } finally {
            pending.remove(token)
            try { NotificationManagerCompat.from(app).cancel(NOTIFICATION_TAG, NOTIFICATION_ID) }
            catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "test notification cleanup failed", error) }
        }
    }

    /** Synthetic StatusBarNotification for Diagnostics; it still goes through the shared real processor. */
    fun synthetic(context: Context, packageName: String, tag: String, postTime: Long = System.currentTimeMillis()): StatusBarNotification {
        val app = context.applicationContext
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pipeline diagnostic")
            .setContentText("Synthetic test only; no personal message.")
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        return StatusBarNotification(
            packageName,
            app.packageName,
            NOTIFICATION_ID,
            tag,
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            postTime
        )
    }
}
