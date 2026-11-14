package com.cue.offlinebrain.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.cue.offlinebrain.diagnostics.CrashLog
import java.util.Calendar

object SystemClockAlarmMirror {
    fun addOneTime(context: Context, triggerAtMillis: Long, label: String): String {
        return try {
            val time = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, time.get(Calendar.HOUR_OF_DAY))
                .putExtra(AlarmClock.EXTRA_MINUTES, time.get(Calendar.MINUTE))
                .putExtra(AlarmClock.EXTRA_MESSAGE, label.take(120))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) {
                CrashLog.record("ALARM", "system Clock app has no ACTION_SET_ALARM handler")
                " The system Clock app was not available, so only the in-app alarm is set."
            } else {
                context.startActivity(intent)
                CrashLog.record("ALARM", "user-opted-in one-time alarm mirrored to the system Clock app")
                " Also added to your phone's Clock app. Both alarms can ring; turn off this option if you hear two alarms."
            }
        } catch (error: Exception) {
            CrashLog.record("ALARM", "system Clock handoff failed", error)
            " The phone's Clock app did not accept the alarm; the in-app reminder is still set."
        }
    }
}
