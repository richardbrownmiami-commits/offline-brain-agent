package com.cue.offlinebrain.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object VoiceSpeechOutput {
    private data class AudioFocusLease(
        val manager: AudioManager,
        val request: AudioFocusRequest?,
        val legacyListener: AudioManager.OnAudioFocusChangeListener?
    )

    suspend fun speakOnce(context: Context, text: String, takeAudioFocus: Boolean = false): Boolean {
        val successful = AtomicBoolean(false)
        val completed = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                var engine: TextToSpeech? = null
                var audioFocus: AudioFocusLease? = null
                val finished = AtomicBoolean(false)
                fun finish(ok: Boolean = false) {
                    if (!finished.compareAndSet(false, true)) return
                    successful.set(ok)
                    runCatching { engine?.stop(); engine?.shutdown() }
                    audioFocus?.let(::abandonAudioFocus)
                    audioFocus = null
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
                            val attributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            current.setAudioAttributes(attributes)
                            audioFocus = if (takeAudioFocus) requestAudioFocus(context, attributes) else null
                            if (takeAudioFocus && audioFocus == null) {
                                CrashLog.record("VOICE", "read-aloud audio focus was unavailable")
                                finish()
                                return@TextToSpeech
                            }
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

    private fun requestAudioFocus(context: Context, attributes: AudioAttributes): AudioFocusLease? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .setAcceptsDelayedFocusGain(false)
                .build()
            if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) AudioFocusLease(manager, request, null) else null
        } else {
            @Suppress("DEPRECATION")
            val listener = AudioManager.OnAudioFocusChangeListener { }
            @Suppress("DEPRECATION")
            val granted = manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) AudioFocusLease(manager, null, listener) else null
        }
    }

    private fun abandonAudioFocus(lease: AudioFocusLease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && lease.request != null) lease.manager.abandonAudioFocusRequest(lease.request)
        else lease.legacyListener?.let { @Suppress("DEPRECATION") lease.manager.abandonAudioFocus(it) }
    }
}
