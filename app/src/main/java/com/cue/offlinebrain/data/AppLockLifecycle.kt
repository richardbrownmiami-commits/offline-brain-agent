package com.cue.offlinebrain.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLockPolicy {
    const val DEFAULT_TIMEOUT_SECONDS = 120
    const val MAX_FRESH_PAUSE_CANDIDATE_MILLIS = 2_000L
    val allowedTimeoutSeconds: List<Int> = listOf(0, 30, 60, 120, 300, 900)

    fun normalizeTimeoutSeconds(seconds: Int): Int =
        seconds.takeIf { it in allowedTimeoutSeconds } ?: DEFAULT_TIMEOUT_SECONDS

    fun timeoutMillis(seconds: Int): Long = normalizeTimeoutSeconds(seconds).toLong() * 1_000L

    /** A cold start (null elapsed time) is always locked when lock is enabled. */
    fun shouldRelock(timeoutSeconds: Int, elapsedBackgroundMillis: Long?): Boolean {
        if (elapsedBackgroundMillis == null || elapsedBackgroundMillis < 0L) return true
        val timeout = timeoutMillis(timeoutSeconds)
        return timeout == 0L || elapsedBackgroundMillis >= timeout
    }

    /** Fake-clock-friendly policy used by unit tests and process resume handling. */
    fun shouldRelockAt(timeoutSeconds: Int, backgroundAtElapsedMillis: Long?, nowElapsedMillis: Long): Boolean {
        if (backgroundAtElapsedMillis == null || nowElapsedMillis < backgroundAtElapsedMillis) return true
        return shouldRelock(timeoutSeconds, nowElapsedMillis - backgroundAtElapsedMillis)
    }

    /** A pause edge refines a delayed process-stop callback only when the two are close together. */
    fun trueBackgroundAt(stopAtElapsedMillis: Long, pauseAtElapsedMillis: Long?): Long =
        pauseAtElapsedMillis?.takeIf {
            it > 0L && it <= stopAtElapsedMillis && stopAtElapsedMillis - it <= MAX_FRESH_PAUSE_CANDIDATE_MILLIS
        } ?: stopAtElapsedMillis
}

data class AppLockRuntimeStatus(
    val timeoutSeconds: Int = AppLockPolicy.DEFAULT_TIMEOUT_SECONDS,
    val lastBackgroundAtElapsedMillis: Long? = null
)

/** Process-wide lock request; saved unlocked state is deliberately never persisted. */
object AppLockLifecycle : DefaultLifecycleObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _lockRequired = MutableStateFlow(false)
    val lockRequired: StateFlow<Boolean> = _lockRequired.asStateFlow()
    private val _runtimeStatus = MutableStateFlow(AppLockRuntimeStatus())
    val runtimeStatus: StateFlow<AppLockRuntimeStatus> = _runtimeStatus.asStateFlow()
    @Volatile private var initialized = false
    @Volatile private var firstActivityGateInitialized = false
    @Volatile private var backgroundGeneration = 0
    @Volatile private var stoppedAtElapsed = 0L
    @Volatile private var activityPauseAtElapsed = 0L
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        try {
            val timeout = AppLockStateStore.timeoutSeconds(context)
            _lockRequired.value = AppLockStateStore.isEnabled(context)
            _runtimeStatus.value = AppLockRuntimeStatus(timeout, _runtimeStatus.value.lastBackgroundAtElapsedMillis)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            initialized = true
            CrashLog.record("SECURITY", "process app-lock observer connected")
        } catch (error: Exception) {
            _lockRequired.value = true
            CrashLog.record("SECURITY", "process app-lock observer could not connect", error)
        }
    }

    /** Called once when the first activity has loaded settings; covers upgrades from older versions. */
    @Synchronized
    fun initializeFirstActivityGate(lockEnabled: Boolean): Boolean {
        if (!firstActivityGateInitialized) {
            firstActivityGateInitialized = true
            if (lockEnabled) _lockRequired.value = true
        }
        return _lockRequired.value
    }

    fun markUnlocked() { _lockRequired.value = false }

    /** Captures the real pause edge; a transient Recents/app switch is not itself a lock event. */
    @Synchronized
    fun noteActivityPaused() {
        try {
            val context = appContext ?: return
            if (!AppLockStateStore.isEnabled(context)) return
            if (activityPauseAtElapsed <= 0L) activityPauseAtElapsed = SystemClock.elapsedRealtime()
        } catch (error: Exception) { CrashLog.record("SECURITY", "activity pause time could not be recorded", error) }
    }

    /** If ProcessLifecycle never entered the background, discard a short pause candidate. */
    @Synchronized
    fun noteActivityResumed() {
        if (stoppedAtElapsed <= 0L) activityPauseAtElapsed = 0L
    }

    override fun onStop(owner: LifecycleOwner) {
        val context = appContext ?: return
        try {
            if (!AppLockStateStore.isEnabled(context)) return
            val generation = ++backgroundGeneration
            val nowElapsed = SystemClock.elapsedRealtime()
            val backgroundAt = AppLockPolicy.trueBackgroundAt(nowElapsed, activityPauseAtElapsed.takeIf { it > 0L })
            activityPauseAtElapsed = 0L
            stoppedAtElapsed = backgroundAt
            val timeoutSeconds = AppLockStateStore.timeoutSeconds(context)
            _runtimeStatus.value = AppLockRuntimeStatus(timeoutSeconds, backgroundAt)
            val timeoutMillis = AppLockPolicy.timeoutMillis(timeoutSeconds)
            if (timeoutMillis <= 0L) {
                if (AppLockStateStore.isEnabled(context)) _lockRequired.value = true
                return
            }
            mainHandler.postDelayed({
                try {
                    if (backgroundGeneration == generation && AppLockStateStore.isEnabled(context)) {
                        _lockRequired.value = true
                        CrashLog.record("SECURITY", "process relock timeout elapsed after ${timeoutSeconds}s")
                    }
                } catch (error: Exception) {
                    CrashLog.record("SECURITY", "process relock timeout callback failed", error)
                }
            }, timeoutMillis)
        } catch (error: Exception) {
            CrashLog.record("SECURITY", "process stop lock handling failed", error)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        try {
            backgroundGeneration++
            val context = appContext ?: return
            val timeoutSeconds = AppLockStateStore.timeoutSeconds(context)
            _runtimeStatus.value = _runtimeStatus.value.copy(timeoutSeconds = timeoutSeconds)
            if (AppLockStateStore.isEnabled(context)) {
                val backgroundAt = stoppedAtElapsed.takeIf { it > 0L }
                val nowElapsed = SystemClock.elapsedRealtime()
                if (AppLockPolicy.shouldRelockAt(timeoutSeconds, backgroundAt, nowElapsed)) _lockRequired.value = true
            }
            stoppedAtElapsed = 0L
        } catch (error: Exception) {
            CrashLog.record("SECURITY", "process start lock handling failed", error)
        }
    }
}
