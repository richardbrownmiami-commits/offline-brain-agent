package com.cue.offlinebrain.voice

enum class SpeechPhase { IDLE, LISTENING, PROCESSING, RESULT, ERROR }

data class SpeechInputState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val partialText: String = "",
    val finalText: String = "",
    val message: String = "Not listening.",
    val secondsRemaining: Int = 12
)

/** Small platform-free state machine; the Android recognizer remains an adapter around it. */
class SpeechInputStateMachine {
    var state: SpeechInputState = SpeechInputState()
        private set

    fun start(): SpeechInputState = set(SpeechInputState(phase = SpeechPhase.LISTENING, message = "Listening…", secondsRemaining = 12))

    fun partial(text: String): SpeechInputState {
        if (state.phase != SpeechPhase.LISTENING) return state
        val safe = text.trim().take(2_000)
        if (safe.isBlank()) return state
        return set(state.copy(partialText = safe, message = "Listening…"))
    }

    fun tick(secondsLeft: Int): SpeechInputState {
        if (state.phase != SpeechPhase.LISTENING) return state
        val left = secondsLeft.coerceIn(0, 12)
        return set(state.copy(secondsRemaining = left))
    }

    fun stopRequested(): SpeechInputState {
        if (state.phase != SpeechPhase.LISTENING) return state
        return set(state.copy(phase = SpeechPhase.PROCESSING, message = "Done, processing…", secondsRemaining = 0))
    }

    fun processing(): SpeechInputState {
        if (state.phase == SpeechPhase.IDLE || state.phase == SpeechPhase.ERROR || state.phase == SpeechPhase.RESULT) return state
        return set(state.copy(phase = SpeechPhase.PROCESSING, message = "Done, processing…", secondsRemaining = 0))
    }

    fun result(recognizedText: String): SpeechInputState {
        val text = recognizedText.trim().take(2_000).ifBlank { state.partialText.trim().take(2_000) }
        return if (text.isBlank()) error("I did not hear anything. Try again, or type your request.")
        else set(state.copy(phase = SpeechPhase.RESULT, finalText = text, message = "Done. Check the text, edit it if you like, then tap Send.", secondsRemaining = 0))
    }

    fun error(message: String): SpeechInputState = set(
        state.copy(phase = SpeechPhase.ERROR, message = message.trim().take(500).ifBlank { "Speech input stopped. Try again or type your request." }, secondsRemaining = 0)
    )

    fun reset(): SpeechInputState = set(SpeechInputState())

    private fun set(value: SpeechInputState): SpeechInputState {
        state = value
        return value
    }
}
