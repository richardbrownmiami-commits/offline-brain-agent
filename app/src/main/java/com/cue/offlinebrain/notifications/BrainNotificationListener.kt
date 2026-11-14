package com.cue.offlinebrain.notifications

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object NotificationAccessState {
    @Volatile var connected: Boolean = false
        internal set

    fun enabled(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (error: Exception) {
        CrashLog.record("NOTIFICATIONS", "could not inspect notification access", error)
        false
    }

    fun lastSavedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences("notification_capture_state", Context.MODE_PRIVATE).getLong("last_saved_at", 0L)

    fun lastConnectedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences("notification_capture_state", Context.MODE_PRIVATE).getLong("listener_connected_at", 0L)

    fun lastDisconnectedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences("notification_capture_state", Context.MODE_PRIVATE).getLong("listener_disconnected_at", 0L)

    fun requestRebind(context: Context) {
        if (Build.VERSION.SDK_INT >= 24 && enabled(context)) {
            try {
                NotificationListenerService.requestRebind(ComponentName(context, BrainNotificationListener::class.java))
                CrashLog.record("NOTIFICATIONS", "requestRebind submitted")
            } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "requestRebind failed", error) }
        }
    }

    /** MIUI may leave access enabled while the binder is disconnected; cycle only this component. */
    fun repair(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24 || !enabled(context)) return false
        requestRebind(context)
        if (connected) return true
        val app = context.applicationContext
        val component = ComponentName(app, BrainNotificationListener::class.java)
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                app.packageManager.setComponentEnabledSetting(
                    component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                handler.postDelayed({
                    try {
                        app.packageManager.setComponentEnabledSetting(
                            component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                        CrashLog.record("NOTIFICATIONS", "listener component toggled to request MIUI rebind")
                        requestRebind(app)
                    } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener component re-enable failed", error) }
                }, 300L)
            } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener component toggle failed", error) }
        }
        return true
    }
}

/** Captured message text is stored locally in the encrypted database only. */
class BrainNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        try {
            super.onListenerConnected()
            NotificationAccessState.connected = true
            val connectedAt = System.currentTimeMillis()
            getSharedPreferences("notification_capture_state", MODE_PRIVATE).edit()
                .putLong("listener_connected_at", connectedAt).apply()
            CrashLog.record("NOTIFICATIONS", "listener connected at=$connectedAt")
            scope.launch {
                try {
                    val active = getActiveNotifications()?.toList().orEmpty()
                    NotificationCapturePipeline.resetActiveGroups()
                    // Register active child notifications before summaries, so an only/summary policy is accurate after rebind.
                    active.sortedBy { (it.notification?.flags ?: 0) and android.app.Notification.FLAG_GROUP_SUMMARY }
                        .forEach { NotificationCapturePipeline.processNotification(applicationContext, it, "active-snapshot") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    CrashLog.record("NOTIFICATIONS", "active notification snapshot failed", error)
                }
            }
        } catch (error: Exception) {
            NotificationAccessState.connected = false
            CrashLog.record("NOTIFICATIONS", "onListenerConnected failed", error)
        }
    }

    override fun onListenerDisconnected() {
        try {
            NotificationAccessState.connected = false
            val disconnectedAt = System.currentTimeMillis()
            getSharedPreferences("notification_capture_state", MODE_PRIVATE).edit()
                .putLong("listener_disconnected_at", disconnectedAt).apply()
            NotificationReplyRegistry.clear()
            CrashLog.record("NOTIFICATIONS", "listener disconnected at=$disconnectedAt; requesting rebind and component repair")
            NotificationAccessState.repair(this)
        } catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "onListenerDisconnected failed", error) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
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

    override fun onDestroy() {
        NotificationAccessState.connected = false
        NotificationReplyRegistry.clear()
        try { scope.cancel() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CrashLog.record("NOTIFICATIONS", "listener coroutine shutdown failed", error) }
        CrashLog.record("NOTIFICATIONS", "listener destroyed")
        super.onDestroy()
    }
}
