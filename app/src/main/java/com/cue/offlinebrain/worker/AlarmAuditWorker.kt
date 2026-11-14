package com.cue.offlinebrain.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.alarm.AlarmScheduler

class AlarmAuditWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as OfflineBrainApplication
        app.database.dao().getEnabledAlarms().forEach { AlarmScheduler.schedule(applicationContext, it) }
        app.database.dao().deleteNotificationsBefore(System.currentTimeMillis() - RETENTION_MILLIS)
        Result.success()
    } catch (_: Exception) { Result.retry() }
    companion object { private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}
