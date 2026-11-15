package com.cue.offlinebrain.notifications

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object NotificationAccessState {
    private val repairCooldown = RepairCooldown()
    private val bindingMachine = NotificationBindingMachine()
    private val mutableBinding = MutableStateFlow(bindingMachine.snapshot())
    val binding = mutableBinding.asStateFlow()

    @Volatile var connected: Boolean = false
        internal set
    @Volatile var appForeground: Boolean = false
        private set

    fun enabled(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (error: Exception) {
        CrashLog.record("NOTIFICATIONS", "could not inspect notification access", error)
        false
    }

    fun lastSavedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_saved_at", 0L)

    fun lastConnectedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("listener_connected_at", 0L)

    fun lastDisconnectedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("listener_disconnected_at", 0L)

    fun lastUnboundAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("listener_unbound_at", 0L)

    fun lastUnbindReason(context: Context): String = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("listener_unbind_reason", "not recorded").orEmpty()

    private fun publish(context: Context) {
        val snapshot = bindingMachine.snapshot()
        mutableBinding.value = snapshot
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("binding_state", snapshot.state.name)
            .putLong("rebind_requests", snapshot.rebindRequests)
            .putLong("listener_connect_count", snapshot.connectCount)
            .putLong("listener_unbind_count", snapshot.unbindCount)
            .putLong("listener_notification_count", snapshot.notificationCount)
            .apply()
    }

    internal fun onConnected(context: Context) {
        connected = true
        bindingMachine.connected(SystemClock.elapsedRealtime())
        val now = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("listener_connected_at", now).apply()
        publish(context)
    }

    internal fun onDisconnected(context: Context) {
        connected = false
        val now = System.currentTimeMillis()
        val current = bindingMachine.snapshot()
        if (current.state != NotificationBindingMachine.State.UNBOUND) {
            bindingMachine.unbound(SystemClock.elapsedRealtime(), NotificationBindingMachine.UnbindReason.DISCONNECTED)
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("listener_disconnected_at", now).apply()
        publish(context)
    }

    internal fun noteNotificationPosted(context: Context) {
        bindingMachine.notificationPosted(SystemClock.elapsedRealtime())
        publish(context)
    }

    /** Requests a platform rebind only when currently unbound; never toggles the component. */
    fun requestOnDemandRebind(context: Context, reason: String): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 24 || !enabled(app)) return false
        if (connected || !bindingMachine.requestRebind()) return false
        publish(app)
        return try {
            NotificationListenerService.requestRebind(ComponentName(app, BrainNotificationListener::class.java))
            CrashLog.record("NOTIFICATIONS", "on-demand rebind requested reason=$reason")
            true
        } catch (error: Exception) {
            bindingMachine.connectionFailed()
            publish(app)
            CrashLog.record("NOTIFICATIONS", "on-demand rebind request failed reason=$reason", error)
            false
        }
    }

    /** Waits at most three seconds for the system callback, then callers use saved local rows. */
    suspend fun ensureConnected(context: Context, reason: String): Boolean {
        val app = context.applicationContext
        if (connected) return true
        if (!enabled(app)) return false
        requestOnDemandRebind(app, reason)
        if (connected) return true
        val result = withTimeoutOrNull(NotificationRebindWaitPolicy.MAX_WAIT_MILLIS) {
            binding.first { it.state == NotificationBindingMachine.State.ACTIVE }
        }
        if (result == null) {
            if (bindingMachine.snapshot().state == NotificationBindingMachine.State.CONNECTING) bindingMachine.connectionFailed()
            publish(app)
        }
        return connected
    }

    fun appResumed(context: Context, requestBinding: Boolean = true) {
        appForeground = true
        BrainNotificationListener.current?.onAppForegrounded()
        if (requestBinding) requestOnDemandRebind(context, "app-resume")
    }

    fun appBackgrounded(context: Context) {
        appForeground = false
        BrainNotificationListener.current?.releaseForBackground()
    }

    suspend fun testUnbindRebind(context: Context): String {
        if (!enabled(context)) return "Notification Access is off; Android cannot connect the listener."
        if (NotificationListenerKeepAliveService.enabled(context)) return "Turn Always on off before testing an on-demand unbind."
        if (connected) BrainNotificationListener.current?.releaseForTest()
        delay(300L)
        val rebound = ensureConnected(context, "user-test")
        return if (rebound) "PASS — listener unbound and reconnected; no automatic repair was used."
        else "NOT VERIFIED — no connection callback arrived within 3 seconds."
    }

    /** Component toggling is a user-invoked recovery action and is protected by the 60-second gate. */
    fun repair(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 24 || !enabled(app)) return false
        if (!repairCooldown.tryAcquire(SystemClock.elapsedRealtime())) return false
        requestOnDemandRebind(app, "manual-repair")
        if (connected) return true
        val component = ComponentName(app, BrainNotificationListener::class.java)
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                app.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                handler.postDelayed({
                    try {
                        app.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                        CrashLog.record("NOTIFICATIONS", "user-requested listener component repair completed")
                        requestOnDemandRebind(app, "manual-repair-after-toggle")
                    } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener component re-enable failed", error) }
                }, 300L)
            } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener component toggle failed", error) }
        }
        return true
    }

    internal fun recordUnbind(context: Context, reason: NotificationBindingMachine.UnbindReason) {
        val nowElapsed = SystemClock.elapsedRealtime()
        bindingMachine.unbound(nowElapsed, reason)
        val nowWall = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("listener_unbound_at", nowWall)
            .putString("listener_unbind_reason", reason.name.lowercase().replace('_', '-'))
            .apply()
        connected = false
        publish(context)
    }

    internal fun idleUnbindReason(nowElapsed: Long, alwaysOn: Boolean): NotificationBindingMachine.UnbindReason? =
        bindingMachine.unbindReason(nowElapsed, appForeground, alwaysOn)

    internal fun currentSnapshot(): NotificationBindingMachine.Snapshot = bindingMachine.snapshot()

    private const val PREFS = "notification_capture_state"
}

/** Captured message text is stored locally in the encrypted database only. */
class BrainNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleUnbind = Runnable { checkIdleAndUnbind() }

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onListenerConnected() {
        try {
            super.onListenerConnected()
            NotificationAccessState.onConnected(applicationContext)
            val connectedAt = System.currentTimeMillis()
            CrashLog.record("NOTIFICATIONS", "listener connected at=$connectedAt")
            resetIdleTimer()
            scope.launch {
                try {
                    val active = getActiveNotifications()?.toList().orEmpty()
                    NotificationCapturePipeline.resetActiveGroups()
                    // Register active child notifications before summaries, then run all rows through the regular save path.
                    active.sortedBy { (it.notification?.flags ?: 0) and android.app.Notification.FLAG_GROUP_SUMMARY }
                        .forEach { NotificationCapturePipeline.processNotification(applicationContext, it, "active-snapshot") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "active notification snapshot failed", error)
                }
            }
        } catch (error: Exception) {
            NotificationAccessState.onDisconnected(applicationContext)
            CrashLog.record("NOTIFICATIONS", "onListenerConnected failed", error)
        }
    }

    override fun onListenerDisconnected() {
        try {
            NotificationAccessState.onDisconnected(applicationContext)
            NotificationReplyRegistry.clear()
            mainHandler.removeCallbacks(idleUnbind)
            CrashLog.record("NOTIFICATIONS", "listener disconnected; automatic component repair is disabled")
        } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "onListenerDisconnected failed", error) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            NotificationAccessState.noteNotificationPosted(applicationContext)
            resetIdleTimer()
            NotificationCapturePipeline.noteCallbackIngress(sbn, "callback")
            scope.launch {
                try { NotificationCapturePipeline.processNotification(applicationContext, sbn, "callback", receiptAlreadyRecorded = true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { NotificationCapturePipeline.recordCallbackDispatchFailure(sbn, "callback", error) }
            }
        } catch (error: Exception) { NotificationCapturePipeline.recordCallbackDispatchFailure(sbn, "callback-start", error) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            val packageName = try { sbn?.packageName.orEmpty() }
            catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "removed-notification package unavailable", error); "unknown" }
            val postTime = try { sbn?.postTime ?: 0L }
            catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "removed-notification time unavailable", error); 0L }
            val key = try { sbn?.key }
            catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "removed-notification key unavailable", error); null }
            NotificationCapturePipeline.removeNotification(packageName, postTime, key, "onNotificationRemoved")
        } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "reply/group cleanup failed", error) }
    }

    internal fun onAppForegrounded() {
        mainHandler.removeCallbacks(idleUnbind)
    }

    internal fun releaseForBackground() {
        if (NotificationListenerKeepAliveService.enabled(applicationContext)) return
        val reason = NotificationAccessState.currentSnapshot().let {
            if (it.state == NotificationBindingMachine.State.ACTIVE) NotificationBindingMachine.UnbindReason.APP_BACKGROUND else null
        } ?: return
        performUnbind(reason)
    }

    internal fun releaseForTest() = performUnbind(NotificationBindingMachine.UnbindReason.USER_TEST)

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(idleUnbind)
        if (!NotificationAccessState.appForeground && !NotificationListenerKeepAliveService.enabled(applicationContext)) {
            mainHandler.postDelayed(idleUnbind, IDLE_UNBIND_MILLIS)
        }
    }

    private fun checkIdleAndUnbind() {
        val alwaysOn = NotificationListenerKeepAliveService.enabled(applicationContext)
        val reason = NotificationAccessState.idleUnbindReason(SystemClock.elapsedRealtime(), alwaysOn)
        if (reason != null) performUnbind(reason)
        else if (!NotificationAccessState.appForeground && !alwaysOn && NotificationAccessState.connected) {
            mainHandler.postDelayed(idleUnbind, IDLE_RECHECK_MILLIS)
        }
    }

    private fun performUnbind(reason: NotificationBindingMachine.UnbindReason) {
        if (Build.VERSION.SDK_INT < 24 || !NotificationAccessState.connected) return
        try {
            mainHandler.removeCallbacks(idleUnbind)
            // requestUnbind must be invoked by the connected listener service itself (API 24+).
            requestUnbind(ComponentName(applicationContext, BrainNotificationListener::class.java))
            NotificationAccessState.recordUnbind(applicationContext, reason)
            NotificationReplyRegistry.clear()
            CrashLog.record("NOTIFICATIONS", "listener requestUnbind reason=${reason.name.lowercase()}")
        } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener requestUnbind failed", error) }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleUnbind)
        if (current === this) current = null
        if (NotificationAccessState.connected) NotificationAccessState.onDisconnected(applicationContext)
        NotificationReplyRegistry.clear()
        try { scope.cancel() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener coroutine shutdown failed", error) }
        CrashLog.record("NOTIFICATIONS", "listener destroyed")
        super.onDestroy()
    }

    companion object {
        private const val IDLE_UNBIND_MILLIS = 30_000L
        private const val IDLE_RECHECK_MILLIS = 1_000L
        @Volatile internal var current: BrainNotificationListener? = null
    }
}
