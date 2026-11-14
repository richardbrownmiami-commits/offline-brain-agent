package com.cue.offlinebrain

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cue.offlinebrain.data.AppLockLifecycle
import com.cue.offlinebrain.data.BrainDatabase
import com.cue.offlinebrain.data.BrainRepository
import com.cue.offlinebrain.data.EncryptedBackup
import com.cue.offlinebrain.data.SettingsStore
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.worker.AlarmAuditWorker
import com.cue.offlinebrain.worker.FeatureWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

class OfflineBrainApplication : Application() {
    val database by lazy { BrainDatabase.get(this) }
    val settings by lazy { SettingsStore(this) }
    val repository by lazy { BrainRepository(applicationContext, database, settings) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _restoreRecoveryError = MutableStateFlow<String?>(null)
    val restoreRecoveryError: StateFlow<String?> = _restoreRecoveryError.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        CrashLog.initialize(this)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Only the interrupted-restore path blocks startup; normal launches do not open Room here.
        if (EncryptedBackup.hasPendingRestoreJournal(this)) {
            try {
                runBlocking(Dispatchers.IO) { EncryptedBackup.recoverPendingRestore(database, settings) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _restoreRecoveryError.value = recoveryMessage(error)
                CrashLog.record("BACKUP", "interrupted restore recovery failed; app features are blocked", error)
            }
        }
        AppLockLifecycle.initialize(this)
        if (_restoreRecoveryError.value == null) {
            applicationScope.launch {
                try {
                    val current = settings.settings.first()
                    FeatureWorkScheduler.configure(this@OfflineBrainApplication, current.backgroundRefreshEnabled, current.autoBackupEnabled)
                    FeatureWorkScheduler.configureNotificationListenerWatch(this@OfflineBrainApplication, current.notificationKeepAliveEnabled)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    CrashLog.record("WORK", "periodic feature schedule could not be restored", error)
                }
            }
            try {
                val audit = PeriodicWorkRequestBuilder<AlarmAuditWorker>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork("alarm-safety-audit", ExistingPeriodicWorkPolicy.KEEP, audit)
                CrashLog.record("WORK", "alarm audit worker enqueued")
            } catch (error: Exception) { CrashLog.record("WORK", "could not enqueue alarm audit worker", error) }
        }
    }

    fun markRestoreRecoveryPending(error: Exception) {
        _restoreRecoveryError.value = recoveryMessage(error)
    }

    suspend fun retryRestoreRecovery(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (EncryptedBackup.hasPendingRestoreJournal(this@OfflineBrainApplication)) {
                EncryptedBackup.recoverPendingRestore(database, settings)
            }
            _restoreRecoveryError.value = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _restoreRecoveryError.value = recoveryMessage(error)
            CrashLog.record("BACKUP", "user retry of interrupted restore recovery failed", error)
            false
        }
    }

    private fun recoveryMessage(error: Exception): String =
        "A previous restore did not finish. Your saved recovery copy is being kept, and other app features are paused. ${error.message ?: "Check available storage and retry."}"
}
