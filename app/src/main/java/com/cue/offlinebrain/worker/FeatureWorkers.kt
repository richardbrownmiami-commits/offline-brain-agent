package com.cue.offlinebrain.worker

import android.content.Context
import android.util.Base64
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.data.EncryptedBackup
import com.cue.offlinebrain.data.KeystoreSecretStore
import com.cue.offlinebrain.data.WorkerCacheKeys
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.net.PublicSkills
import com.cue.offlinebrain.notifications.NotificationAccessState
import com.cue.offlinebrain.notifications.NotificationListenerKeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object FeatureWorkPolicy {
    const val REQUIRE_NETWORK = true
    const val REQUIRE_BATTERY_NOT_LOW = true
    const val REFRESH_INTERVAL_HOURS = 12L
    const val BACKUP_INTERVAL_HOURS = 24L
    const val BACKOFF_SECONDS = 30L
}

object FeatureWorkScheduler {
    const val REFRESH_NAME = "user-enabled-content-refresh"
    const val BACKUP_NAME = "user-enabled-encrypted-backup"
    const val LISTENER_HEALTH_NAME = "notification-listener-health-check"
    private const val RETENTION_NAME = "local-privacy-retention-cleanup"

    fun configure(context: Context, refreshEnabled: Boolean, backupEnabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (refreshEnabled) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (FeatureWorkPolicy.REQUIRE_NETWORK) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(FeatureWorkPolicy.REQUIRE_BATTERY_NOT_LOW)
                .build()
            val request = PeriodicWorkRequestBuilder<ContentRefreshWorker>(FeatureWorkPolicy.REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FeatureWorkPolicy.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(REFRESH_NAME)
                .build()
            manager.enqueueUniquePeriodicWork(REFRESH_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else manager.cancelUniqueWork(REFRESH_NAME)

        if (backupEnabled) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (FeatureWorkPolicy.REQUIRE_NETWORK) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(FeatureWorkPolicy.REQUIRE_BATTERY_NOT_LOW)
                .build()
            val request = PeriodicWorkRequestBuilder<EncryptedBackupWorker>(FeatureWorkPolicy.BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FeatureWorkPolicy.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(BACKUP_NAME)
                .build()
            manager.enqueueUniquePeriodicWork(BACKUP_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else manager.cancelUniqueWork(BACKUP_NAME)
        val retention = PeriodicWorkRequestBuilder<PrivacyRetentionWorker>(FeatureWorkPolicy.BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(FeatureWorkPolicy.REQUIRE_BATTERY_NOT_LOW).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FeatureWorkPolicy.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(RETENTION_NAME)
            .build()
        manager.enqueueUniquePeriodicWork(RETENTION_NAME, ExistingPeriodicWorkPolicy.UPDATE, retention)
        CrashLog.record("WORK", "user-enabled periodic refresh and backup schedule updated")
    }

    fun configureNotificationListenerWatch(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(LISTENER_HEALTH_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<NotificationListenerHealthWorker>(6, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FeatureWorkPolicy.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(LISTENER_HEALTH_NAME)
            .build()
        manager.enqueueUniquePeriodicWork(LISTENER_HEALTH_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

object FeatureWorkStatus {
    fun record(context: Context, name: String, result: String) {
        runCatching {
            context.applicationContext.getSharedPreferences("feature_work_status", Context.MODE_PRIVATE).edit()
                .putLong("last_run_$name", System.currentTimeMillis())
                .putString("last_result_$name", result.take(80))
                .apply()
        }
    }
}

class NotificationListenerHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as? OfflineBrainApplication ?: return@withContext Result.failure()
            val prefs = app.settings.settings.first()
            val now = System.currentTimeMillis()
            val state = applicationContext.getSharedPreferences("notification_capture_state", Context.MODE_PRIVATE)
            state.edit().putLong("listener_check_at", now).apply()
            if (!prefs.notificationKeepAliveEnabled || !NotificationListenerKeepAliveService.enabled(applicationContext)) {
                state.edit().putString("listener_check_result", "disabled").apply()
                FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.LISTENER_HEALTH_NAME, "disabled")
                return@withContext Result.success()
            }
            if (!NotificationAccessState.enabled(applicationContext)) {
                state.edit().putString("listener_check_result", "permission off").apply()
                FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.LISTENER_HEALTH_NAME, "permission off")
                CrashLog.record("NOTIFICATIONS", "periodic listener check found access permission off")
                return@withContext Result.success()
            }
            val result = if (NotificationAccessState.connected) "connected" else "disconnected; user repair only"
            state.edit().putString("listener_check_result", result).apply()
            FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.LISTENER_HEALTH_NAME, result)
            CrashLog.record("NOTIFICATIONS", "WorkManager listener health check result=$result")
            Result.success()
        } catch (error: Exception) {
            FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.LISTENER_HEALTH_NAME, "retry after error")
            CrashLog.record("NOTIFICATIONS", "WorkManager listener health check failed (${error.javaClass.simpleName})")
            Result.retry()
        }
    }
}

class PrivacyRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as? OfflineBrainApplication ?: return@withContext Result.failure()
            val prefs = app.settings.settings.first()
            val before = System.currentTimeMillis() - prefs.notificationRetentionDays * 24L * 60L * 60L * 1000L
            app.repository.pruneLocalHistory(before)
            FeatureWorkStatus.record(applicationContext, "local-privacy-retention-cleanup", "success")
            CrashLog.record("PRIVACY", "local history retention cleanup completed")
            Result.success()
        } catch (error: Exception) {
            FeatureWorkStatus.record(applicationContext, "local-privacy-retention-cleanup", "retry after error")
            CrashLog.record("PRIVACY", "local history retention cleanup failed (${error.javaClass.simpleName})")
            Result.retry()
        }
    }
}

class ContentRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? OfflineBrainApplication ?: return@withContext Result.failure()
        try {
            val prefs = app.settings.settings.first()
            if (!prefs.backgroundRefreshEnabled) {
                FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.REFRESH_NAME, "disabled")
                return@withContext Result.success()
            }
            val tasks = mutableListOf<Pair<String, () -> String>>()
            tasks += WorkerCacheKeys.weather(prefs.defaultCity) to { PublicSkills().weather(prefs.defaultCity) }
            tasks += WorkerCacheKeys.headlines(prefs.newsFeed) to { PublicSkills().headlines(prefs.newsFeed) }
            if (prefs.customApiUrl.isNotBlank()) {
                val headers = app.settings.customApiHeaders().lines().mapNotNull { line ->
                    val split = line.indexOf(':')
                    if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
                }.toMap()
                tasks += WorkerCacheKeys.customApi(prefs.customApiUrl, prefs.customJsonPath, headers) to { PublicSkills().customGet(prefs.customApiUrl, headers, prefs.customJsonPath) }
            }
            var failed = false
            tasks.forEach { (key, fetch) ->
                try {
                    val result = fetch().take(4000)
                    app.repository.saveBackgroundCache(key, result)
                } catch (error: Exception) {
                    failed = true
                    // Do not log URLs, response bodies, notification contents, or API headers.
                    CrashLog.record("WORK", "background ${key.substringBefore(':')} refresh failed (${error.javaClass.simpleName})")
                }
            }
            val before = System.currentTimeMillis() - prefs.notificationRetentionDays * 24L * 60L * 60L * 1000L
            app.repository.pruneLocalHistory(before)
            val outcome = if (failed) Result.retry() else Result.success()
            FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.REFRESH_NAME, if (failed) "retry" else "success")
            outcome
        } catch (error: Exception) {
            FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.REFRESH_NAME, "retry after error")
            CrashLog.record("WORK", "background content refresh failed (${error.javaClass.simpleName})")
            Result.retry()
        }
    }
}

class EncryptedBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? OfflineBrainApplication ?: return@withContext Result.failure()
        try {
            if (!app.settings.settings.first().autoBackupEnabled) {
                FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.BACKUP_NAME, "disabled")
                return@withContext Result.success()
            }
            val passphrase = KeystoreSecretStore.get(applicationContext, SECRET_KEY) ?: createPassphrase(applicationContext)
            val bytes = EncryptedBackup.export(app.database, passphrase, app.settings)
            val directory = File(applicationContext.filesDir, "automatic-backups").apply { mkdirs() }
            val target = File(directory, "offline-brain-latest.cueb")
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
            directory.listFiles()?.filter { it.isFile && it.name != target.name && it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
                ?.forEach { runCatching { it.delete() } }
            CrashLog.record("BACKUP", "encrypted automatic backup saved in private app storage")
            FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.BACKUP_NAME, "success")
            Result.success()
        } catch (error: Exception) {
            FeatureWorkStatus.record(applicationContext, FeatureWorkScheduler.BACKUP_NAME, "retry after error")
            CrashLog.record("BACKUP", "automatic encrypted backup failed (${error.javaClass.simpleName})")
            Result.retry()
        }
    }

    private fun createPassphrase(context: Context): String {
        val random = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = Base64.encodeToString(random, Base64.NO_WRAP)
        KeystoreSecretStore.put(context, SECRET_KEY, secret)
        random.fill(0)
        return secret
    }

    companion object { private const val SECRET_KEY = "scheduled_backup_key" }
}
