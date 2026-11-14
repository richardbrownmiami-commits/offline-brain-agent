package com.cue.offlinebrain.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cue.offlinebrain.MainActivity
import com.cue.offlinebrain.R
import com.cue.offlinebrain.diagnostics.CrashLog

/** Optional, user-started visible service. It never captures notifications by itself. */
class NotificationListenerKeepAliveService : Service() {
    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, makeNotification())
        CrashLog.record("NOTIFICATIONS", "user-visible listener keep-alive service started")
        return START_STICKY
    }

    private fun makeNotification(): Notification {
        val open = PendingIntent.getActivity(this, REQUEST_OPEN, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, NotificationListenerKeepAliveService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, REQUEST_STOP, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Notification listener keep-alive")
            .setContentText("Helps MIUI keep notification access connected. Saved history remains if stopped.")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Notification listener status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible while the optional MIUI listener keep-alive is on"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
    }

    override fun onDestroy() {
        CrashLog.record("NOTIFICATIONS", "listener keep-alive service stopped")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "notification_listener_keep_alive"
        private const val NOTIFICATION_ID = 7110
        private const val REQUEST_OPEN = 7111
        private const val REQUEST_STOP = 7112
        private const val ACTION_STOP = "com.cue.offlinebrain.STOP_NOTIFICATION_KEEP_ALIVE"
        private const val PREFS = "notification_keep_alive"
        private const val ENABLED = "enabled"

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
        fun start(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply()
            val intent = Intent(context, NotificationListenerKeepAliveService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent) else context.startService(intent) }
                .onFailure { CrashLog.record("NOTIFICATIONS", "foreground listener keep-alive could not start", it) }
        }
        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
            runCatching { context.stopService(Intent(context, NotificationListenerKeepAliveService::class.java)) }
                .onFailure { CrashLog.record("NOTIFICATIONS", "foreground listener keep-alive could not stop", it) }
        }
    }
}
