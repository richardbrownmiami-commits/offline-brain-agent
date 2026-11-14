package com.cue.offlinebrain.voice

/** Platform-free decisions shared by the speech controllers and host tests. */
object SpeechRetryPolicy {
    const val RETRY_DELAY_MS = 400L
    fun shouldRetry(errorCode: Int, retryAlreadyUsed: Boolean, cancelled: Boolean): Boolean =
        !cancelled && !retryAlreadyUsed && (errorCode == 8 || errorCode == 5)

    fun shouldFallbackToVosk(mode: VoiceEngineMode, retryFailed: Boolean): Boolean =
        mode == VoiceEngineMode.AUTO && retryFailed
}

data class SpeechDiagnosticEvent(
    val engine: String,
    val errorCode: Int?,
    val status: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

object SpeechEventRing {
    const val CAPACITY = 5
    fun append(current: List<SpeechDiagnosticEvent>, event: SpeechDiagnosticEvent): List<SpeechDiagnosticEvent> =
        (current + event.copy(engine = clean(event.engine), status = clean(event.status))).takeLast(CAPACITY)

    private fun clean(value: String): String = value
        .replace(Regex("(?i)(transcript|recognized text|partial text)\\s*[:=].*"), "speech text redacted")
        .replace(Regex("[\\r\\n\\t]+"), " ").take(220)
}

object RecognitionServiceOrder {
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    val comparator: Comparator<String> = compareBy<String> { if (it == GOOGLE_PACKAGE) 0 else 1 }.thenBy { it }
}
