package com.cue.offlinebrain.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cue.offlinebrain.alarm.AlarmAnnouncementService
import com.cue.offlinebrain.alarm.AlarmReceiver

class DiagnosticAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST) return
        val at = System.currentTimeMillis()
        runCatching { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().putLong(KEY_FIRED_AT, at).apply() }
            .onSuccess { CrashLog.record("DIAGNOSTIC", "one-minute test alarm receiver fired") }
            .onFailure { CrashLog.record("DIAGNOSTIC", "could not record test alarm delivery", it) }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AlarmAnnouncementService::class.java)
                .putExtra(AlarmReceiver.EXTRA_ID, TEST_ALARM_ID)
                .putExtra(AlarmReceiver.EXTRA_LABEL, "Diagnostics alarm test"))
        }.onFailure { CrashLog.record("DIAGNOSTIC", "test alarm announcement service failed to start", it) }
    }

    companion object {
        const val ACTION_TEST = "com.cue.offlinebrain.DIAGNOSTIC_ALARM_TEST"
        const val KEY_FIRED_AT = "diagnostic_alarm_fired_at"
        private const val TEST_ALARM_ID = -94002L
    }
}
