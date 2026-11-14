package com.cue.offlinebrain.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cue.offlinebrain.MainActivity
import com.cue.offlinebrain.data.AlarmEntity
import com.cue.offlinebrain.diagnostics.CrashLog

object AlarmScheduler {
    /** Schedules a user-visible, one-shot Android alarm-clock event. */
    fun schedule(context: Context, alarm: AlarmEntity): Boolean {
        if (!alarm.enabled) {
            CrashLog.record("ALARM", "schedule skipped id=${alarm.id}: disabled")
            return false
        }
        if (alarm.triggerAt <= System.currentTimeMillis()) {
            CrashLog.record("ALARM", "schedule rejected id=${alarm.id}: trigger is not in the future")
            return false
        }
        return try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: throw IllegalStateException("AlarmManager unavailable")
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                CrashLog.record("ALARM", "exact alarm permission is not granted for id=${alarm.id}")
                return false
            }
            val requestCode = alarm.id.hashCode()
            val show = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_ALARM_ID, alarm.id)
            val showIntent = PendingIntent.getActivity(
                context, requestCode xor SHOW_REQUEST_XOR, show,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val fire = Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
            val operation = PendingIntent.getBroadcast(
                context, requestCode, fire,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val info = AlarmManager.AlarmClockInfo(alarm.triggerAt, showIntent)
            CrashLog.record("ALARM", "calling setAlarmClock id=${alarm.id} triggerAt=${alarm.triggerAt}")
            manager.setAlarmClock(info, operation)
            CrashLog.record("ALARM", "setAlarmClock accepted id=${alarm.id}")
            true
        } catch (error: SecurityException) {
            CrashLog.record("ALARM", "setAlarmClock blocked id=${alarm.id}", error)
            false
        } catch (error: Exception) {
            CrashLog.record("ALARM", "schedule failed id=${alarm.id}", error)
            false
        }
    }

    fun cancel(context: Context, id: Long) {
        runCatching {
            val requestCode = id.hashCode()
            val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_ID, id)
            val pending = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pending)
                pending.cancel()
                CrashLog.record("ALARM", "cancelled id=$id")
            } else CrashLog.record("ALARM", "cancel: no pending alarm for id=$id")
        }.onFailure { CrashLog.record("ALARM", "cancel failed id=$id", it) }
    }

    const val EXTRA_OPEN_ALARM_ID = "open_alarm_id"
    const val ACTION_FIRE = "com.cue.offlinebrain.ALARM_FIRE"
    private const val SHOW_REQUEST_XOR = 0x13572468
}
