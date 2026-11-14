package com.cue.offlinebrain.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicitly user-started, bounded macro capture. Steps live only in memory until Stop-and-save. */
object MacroCaptureSession {
    private val _steps = MutableStateFlow<List<String>>(emptyList())
    val steps = _steps.asStateFlow()

    @Volatile
    var isActive: Boolean = false
        private set

    @Synchronized
    fun start() {
        _steps.value = emptyList()
        isActive = true
    }

    @Synchronized
    fun append(step: String) {
        if (!isActive) return
        val clean = step.trim().replace(Regex("\\s+"), " ").take(160)
        if (clean.isBlank()) return
        val current = _steps.value
        if (current.size >= MAX_STEPS || current.lastOrNull() == clean) return
        _steps.value = current + clean
    }

    @Synchronized
    fun stopAndTake(): List<String> {
        isActive = false
        val captured = _steps.value.toList()
        _steps.value = emptyList()
        return captured
    }

    private const val MAX_STEPS = 50
}
