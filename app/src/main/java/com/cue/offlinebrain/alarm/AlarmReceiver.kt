package com.cue.offlinebrain.alarm

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.data.AlarmEntity
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

object AlarmDismissalPolicy {
    fun removeAfterStop(alarm: AlarmEntity?): Boolean = alarm != null && alarm.cron == null
    fun replaceAfterSnooze(alarm: AlarmEntity?): Boolean = alarm != null && alarm.cron == null
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CrashLog.record("ALARM", "receiver entered action=${intent.action ?: "fire"}")
        if (intent.action == ACTION_STOP) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val id = intent.getLongExtra(EXTRA_ID, -1L)
                    if (id >= 0L) {
                        val app = context.applicationContext as OfflineBrainApplication
                        val alarm = app.database.dao().getAlarm(id)
                        if (AlarmDismissalPolicy.removeAfterStop(alarm)) {
                            AlarmScheduler.cancel(context, id)
                            app.database.dao().deleteAlarm(id)
                            CrashLog.record("ALARM", "dismissed one-time alarm removed id=$id")
                        }
                    }
                } catch (error: Exception) { CrashLog.record("ALARM", "stop action failed", error) }
                finally {
                    runCatching { context.stopService(Intent(context, AlarmAnnouncementService::class.java)) }
                        .onFailure { CrashLog.record("ALARM", "announcement service stop failed", it) }
                    CrashLog.record("ALARM", "stop action handled")
                    pending.finish()
                }
            }
            return
        }
        if (intent.action == ACTION_SNOOZE) {
            val label = intent.getStringExtra(EXTRA_LABEL) ?: "Reminder"
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val app = context.applicationContext as OfflineBrainApplication
                    val snoozeMinutes = AlarmPreferences.snoozeMinutes(context)
                    val triggerAt = System.currentTimeMillis() + snoozeMinutes * 60_000L
                    val originalId = intent.getLongExtra(EXTRA_ID, -1L)
                    if (originalId >= 0L) {
                        val original = app.database.dao().getAlarm(originalId)
                        if (AlarmDismissalPolicy.replaceAfterSnooze(original)) {
                            AlarmScheduler.cancel(context, originalId)
                            app.database.dao().deleteAlarm(originalId)
                            CrashLog.record("ALARM", "snoozed one-time alarm replaced by a temporary snooze id=$originalId")
                        }
                    }
                    val alarmId = app.database.dao().addAlarm(AlarmEntity(label = label, triggerAt = triggerAt))
                    val scheduled = AlarmScheduler.schedule(context, AlarmEntity(id = alarmId, label = label, triggerAt = triggerAt))
                    CrashLog.record("ALARM", "snooze saved id=$alarmId minutes=$snoozeMinutes scheduled=$scheduled")
                } catch (error: Exception) {
                    CrashLog.record("ALARM", "snooze failed", error)
                } finally {
                    runCatching { context.stopService(Intent(context, AlarmAnnouncementService::class.java)) }
                        .onFailure { CrashLog.record("ALARM", "announcement service stop after snooze failed", it) }
                    pending.finish()
                }
            }
            return
        }
        val alarmId = intent.getLongExtra(EXTRA_ID, -1L)
        if (alarmId < 0) {
            CrashLog.record("ALARM", "receiver ignored missing alarm id")
            return
        }
        CrashLog.record("ALARM", "alarm fired id=$alarmId")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as OfflineBrainApplication
                val dao = app.database.dao()
                val alarm = dao.getAlarm(alarmId)
                if (alarm == null) {
                    CrashLog.record("ALARM", "no database row for fired id=$alarmId")
                    return@launch
                }
                if (!alarm.enabled) {
                    CrashLog.record("ALARM", "disabled alarm ignored id=$alarmId")
                    return@launch
                }
                if (alarm.cron != null) {
                    val next = CronSchedule.parse(alarm.cron).nextAfter(LocalDateTime.now())
                    if (next != null) {
                        val nextMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        dao.updateAlarmTime(alarm.id, nextMillis)
                        val rescheduled = AlarmScheduler.schedule(context, alarm.copy(triggerAt = nextMillis))
                        CrashLog.record("ALARM", "recurring alarm rearmed id=$alarmId next=$nextMillis success=$rescheduled")
                    }
                } else {
                    dao.disableAlarm(alarm.id)
                    CrashLog.record("ALARM", "one-shot alarm disabled in database id=$alarmId")
                }
                val service = Intent(context, AlarmAnnouncementService::class.java)
                    .putExtra(EXTRA_ID, alarm.id).putExtra(EXTRA_LABEL, alarm.label)
                try {
                    ContextCompat.startForegroundService(context, service)
                    CrashLog.record("ALARM", "announcement foreground service requested id=$alarmId")
                } catch (error: Exception) {
                    CrashLog.record("ALARM", "announcement foreground service could not start id=$alarmId", error)
                }
            } catch (error: Exception) {
                CrashLog.record("ALARM", "receiver processing failed id=$alarmId", error)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_LABEL = "alarm_label"
        const val ACTION_SNOOZE = "com.cue.offlinebrain.SNOOZE"
        const val ACTION_STOP = "com.cue.offlinebrain.STOP_ALARM"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CrashLog.record("ALARM", "recovery receiver entered action=${intent.action}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as OfflineBrainApplication
                val alarms = app.database.dao().getEnabledAlarms()
                CrashLog.record("ALARM", "recovery found ${alarms.size} enabled alarm(s)")
                alarms.forEach { alarm ->
                    val scheduled = AlarmScheduler.schedule(context, alarm)
                    CrashLog.record("ALARM", "recovery schedule id=${alarm.id} success=$scheduled")
                }
            } catch (error: Exception) {
                CrashLog.record("ALARM", "recovery failed", error)
            } finally { pending.finish() }
        }
    }
}
