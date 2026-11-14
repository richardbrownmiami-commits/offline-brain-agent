package com.cue.offlinebrain.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cue.offlinebrain.MainActivity
import com.cue.offlinebrain.R
import com.cue.offlinebrain.diagnostics.CrashLog
import java.util.Locale

class AlarmAnnouncementService : Service() {
    private var tts: TextToSpeech? = null
    private var ringtone: Ringtone? = null
    private var label = "Reminder"
    private var alarmId = -1L
    private var hasStarted = false
    private var activeChannelId = CHANNEL_ID
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.record("ALARM", "announcement service created")
        activeChannelId = AlarmPreferences.channelId(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLog.record("ALARM", "announcement service start command")
        if (intent?.action == AlarmReceiver.ACTION_STOP) {
            stopAlarm("user stop")
            return START_NOT_STICKY
        }
        label = intent?.getStringExtra(AlarmReceiver.EXTRA_LABEL)?.take(180) ?: "Reminder"
        alarmId = intent?.getLongExtra(AlarmReceiver.EXTRA_ID, -1L) ?: -1L
        try {
            startForeground(NOTIFICATION_ID, makeNotification())
            CrashLog.record("ALARM", "foreground notification shown id=$alarmId")
            acquireWakeLock()
            startAlarmTone()
            initializeTtsAndSpeak()
        } catch (error: Exception) {
            CrashLog.record("ALARM", "service start failed id=$alarmId", error)
            stopAlarm("service failure")
        }
        return START_NOT_STICKY
    }

    private fun startAlarmTone() {
        try {
            val uri = AlarmPreferences.selectedSoundUri(applicationContext)
            if (uri == null) {
                CrashLog.record("ALARM", "no system alarm or notification ringtone configured")
                return
            }
            val tone = RingtoneManager.getRingtone(applicationContext, uri)
            if (tone == null) {
                CrashLog.record("ALARM", "system ringtone service returned no ringtone")
                return
            }
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
            tone.play()
            ringtone = tone
            hasStarted = true
            CrashLog.record("ALARM", "alarm ringtone playback started id=$alarmId")
        } catch (error: Exception) {
            CrashLog.record("ALARM", "alarm ringtone playback failed id=$alarmId", error)
        }
    }

    private fun initializeTtsAndSpeak() {
        CrashLog.record("ALARM", "initializing TTS after label assignment id=$alarmId")
        tts = TextToSpeech(applicationContext) { status ->
            try {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    CrashLog.record("ALARM", "TTS initialization failed id=$alarmId status=$status")
                    return@TextToSpeech
                }
                engine.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                val language = engine.setLanguage(Locale.getDefault())
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    CrashLog.record("ALARM", "TTS locale unavailable id=$alarmId locale=${Locale.getDefault()}")
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { CrashLog.record("ALARM", "TTS speech started id=$alarmId") }
                    override fun onDone(utteranceId: String?) { CrashLog.record("ALARM", "TTS speech completed id=$alarmId; alarm remains active") }
                    @Suppress("DEPRECATION")
                    @Deprecated("Required by Android's TTS listener contract; newer engines use the error-code overload")
                    override fun onError(utteranceId: String?) { CrashLog.record("ALARM", "TTS speech error id=$alarmId") }
                    override fun onError(utteranceId: String?, errorCode: Int) { CrashLog.record("ALARM", "TTS speech error id=$alarmId code=$errorCode") }
                })
                val result = engine.speak(label, TextToSpeech.QUEUE_FLUSH, null, "offline-brain-alarm-$alarmId")
                if (result == TextToSpeech.ERROR) CrashLog.record("ALARM", "TTS rejected utterance id=$alarmId")
                else CrashLog.record("ALARM", "TTS utterance queued id=$alarmId")
            } catch (error: Exception) {
                CrashLog.record("ALARM", "TTS callback failed id=$alarmId", error)
            }
        }
    }

    private fun makeNotification(): Notification {
        val open = PendingIntent.getActivity(this, OPEN_REQUEST, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozeIntent = Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SNOOZE)
            .putExtra(AlarmReceiver.EXTRA_LABEL, label).putExtra(AlarmReceiver.EXTRA_ID, alarmId)
        val snooze = PendingIntent.getBroadcast(this, alarmId.hashCode() xor SNOOZE_REQUEST_XOR, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP)
            .putExtra(AlarmReceiver.EXTRA_ID, alarmId)
        val stop = PendingIntent.getBroadcast(this, alarmId.hashCode() xor STOP_REQUEST_XOR, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozeMinutes = AlarmPreferences.snoozeMinutes(this)
        return NotificationCompat.Builder(this, activeChannelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Offline Brain alarm")
            .setContentText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(label))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .addAction(0, "Snooze $snoozeMinutes min", snooze)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                ?: throw IllegalStateException("NotificationManager unavailable")
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(activeChannelId, "Alarms and reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Exact-time alarms and reminders"
                    setSound(AlarmPreferences.selectedSoundUri(this@AlarmAnnouncementService), AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
            CrashLog.record("ALARM", "high-importance alarm channel checked id=$activeChannelId")
        } catch (error: Exception) { CrashLog.record("ALARM", "notification channel creation failed", error) }
    }

    private fun stopAlarm(reason: String) {
        runCatching { ringtone?.stop() }.onFailure { CrashLog.record("ALARM", "ringtone stop failed", it) }
        ringtone = null
        releaseWakeLock()
        runCatching { tts?.stop() }.onFailure { CrashLog.record("ALARM", "TTS stop failed", it) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        CrashLog.record("ALARM", "alarm stopped id=$alarmId reason=$reason")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { ringtone?.stop() }
        ringtone = null
        releaseWakeLock()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        CrashLog.record("ALARM", "announcement service destroyed id=$alarmId")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val manager = getSystemService(POWER_SERVICE) as? PowerManager
                ?: throw IllegalStateException("PowerManager unavailable")
            val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offline-brain:alarm")
            lock.setReferenceCounted(false)
            lock.acquire(10 * 60 * 1000L)
            wakeLock = lock
            CrashLog.record("ALARM", "partial wake lock acquired for up to 10 minutes id=$alarmId")
        } catch (error: Exception) { CrashLog.record("ALARM", "wake lock acquisition failed id=$alarmId", error) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        runCatching { if (lock?.isHeld == true) lock.release() }
            .onFailure { CrashLog.record("ALARM", "wake lock release failed id=$alarmId", it) }
    }

    companion object {
        const val CHANNEL_ID = "brain_alarms_v2"
        const val NOTIFICATION_ID = 7104
        fun channelId(context: android.content.Context): String = AlarmPreferences.channelId(context)
        private const val OPEN_REQUEST = 7105
        private const val SNOOZE_REQUEST_XOR = 0x5A5A5A5A
        private const val STOP_REQUEST_XOR = 0x25252525
    }
}
