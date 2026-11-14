package com.cue.offlinebrain.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object VoiceSpeechOutput {
    suspend fun speakOnce(context: Context, text: String): Boolean {
        val successful = AtomicBoolean(false)
        val completed = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                var engine: TextToSpeech? = null
                val finished = AtomicBoolean(false)
                fun finish(ok: Boolean = false) {
                    if (!finished.compareAndSet(false, true)) return
                    successful.set(ok)
                    runCatching { engine?.stop(); engine?.shutdown() }
                    if (continuation.isActive) continuation.resume(Unit)
                }
                try {
                    engine = TextToSpeech(context.applicationContext) { status ->
                        try {
                            val current = engine
                            if (status != TextToSpeech.SUCCESS || current == null) {
                                CrashLog.record("VOICE", "reply TTS initialization failed status=$status")
                                finish()
                                return@TextToSpeech
                            }
                            current.setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                            val languageStatus = current.setLanguage(Locale.getDefault())
                            if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                                CrashLog.record("VOICE", "reply TTS language unavailable locale=${Locale.getDefault()}")
                                finish()
                                return@TextToSpeech
                            }
                            current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) { CrashLog.record("VOICE", "voice reply playback started") }
                                override fun onDone(utteranceId: String?) { CrashLog.record("VOICE", "voice reply playback completed"); finish(true) }
                                @Suppress("DEPRECATION")
                                @Deprecated("Required by Android's TTS listener contract; newer engines use the error-code overload")
                                override fun onError(utteranceId: String?) { CrashLog.record("VOICE", "voice reply playback failed"); finish() }
                                override fun onError(utteranceId: String?, errorCode: Int) { CrashLog.record("VOICE", "voice reply playback failed code=$errorCode"); finish() }
                            })
                            if (current.speak(text.take(2500), TextToSpeech.QUEUE_FLUSH, null, "voice-reply") == TextToSpeech.ERROR) {
                                CrashLog.record("VOICE", "TTS rejected voice reply")
                                finish()
                            }
                        } catch (error: Exception) {
                            CrashLog.record("VOICE", "voice reply TTS callback failed", error)
                            finish()
                        }
                    }
                    continuation.invokeOnCancellation { runCatching { engine?.stop(); engine?.shutdown() } }
                } catch (error: Exception) {
                    CrashLog.record("VOICE", "could not create TTS engine", error)
                    finish()
                }
            }
        }
        if (completed == null) CrashLog.record("VOICE", "voice reply TTS timed out")
        return completed != null && successful.get()
    }
}
