package com.cue.offlinebrain.voice

data class SpeechSessionUiState(
    val engine: String = "",
    val status: String = "Not listening.",
    val listening: Boolean = false,
    val processing: Boolean = false,
    val recognizedText: String = "",
    val errorCode: Int? = null,
    val diagnosticOnly: Boolean = false
)
