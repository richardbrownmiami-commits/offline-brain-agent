package com.cue.offlinebrain.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object SpeechDiagnostics {
    @Volatile var lastError: String = "No speech errors recorded."; private set
    @Volatile var lastStatus: String = "Not listening."; private set
    @Volatile var events: List<SpeechDiagnosticEvent> = emptyList(); private set
    private val _eventFlow = MutableStateFlow<List<SpeechDiagnosticEvent>>(emptyList())
    val eventFlow = _eventFlow.asStateFlow()
    @Synchronized fun record(engine: String, code: Int?, status: String) {
        events = SpeechEventRing.append(events, SpeechDiagnosticEvent(engine, code, status))
        _eventFlow.value = events
    }
    fun status(value: String) { lastStatus = value.take(220) }
    fun error(value: String) { lastError = value.take(220); lastStatus = lastError; CrashLog.record("VOICE", "Android speech error: $lastError") }
}

/** Serial, main-thread owner of one in-app Android recognizer. No activity/RecognizerIntent UI is launched. */
class AndroidSpeechInputController(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var recognizer: SpeechRecognizer? = null
    private var onPartial: (String) -> Unit = {}
    private var onFinal: (String) -> Unit = {}
    private var onStatus: (String) -> Unit = {}
    private var onRetryFailed: ((Int, String) -> Unit)? = null
    private var engineMode = VoiceEngineMode.AUTO
    private var retryUsed = false
    private var generation = 0
    private var active = false
    private var ready = false
    private var startupWatchdog: Runnable? = null
    private var manualStop = false
    private var lastPartial = ""
    private var currentLanguage = "en-IN"
    private val state = SpeechInputStateMachine()

    /** onRetryFailed is invoked only after the single 400ms busy/client retry fails. */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onStatus: (String) -> Unit,
              mode: VoiceEngineMode = VoiceEngineMode.AUTO, onRetryFailed: ((Int, String) -> Unit)? = null) {
        assertMain()
        cleanupRecognizer(cancel = true)
        this.onPartial = onPartial; this.onFinal = onFinal; this.onStatus = onStatus
        this.engineMode = mode; this.onRetryFailed = onRetryFailed
        retryUsed = false; ready = false; manualStop = false; lastPartial = ""; currentLanguage = "en-IN"
        generation++; active = true; state.start()
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                finishWithFallback(SpeechRecognizer.ERROR_CLIENT, "No Android speech service is installed. Install or enable a speech recognition service in phone settings, or use Offline mode."); return
            }
            if (!requestFocus()) {
                finishWithFallback(SpeechRecognizer.ERROR_CLIENT, "Audio focus is unavailable, so speech input cannot start. Stop other audio playback and try again."); return
            }
            createAndListen(generation)
        } catch (t: Throwable) {
            if (t is ThreadDeath) throw t
            CrashLog.record("VOICE", "Android speech start failed", t)
            finishWithFallback(SpeechRecognizer.ERROR_CLIENT, "Android speech recognition could not start. Check microphone permission and enabled speech services, then try again.")
        }
    }

    fun stop() {
        assertMain(); if (!active) return
        cancelStartupWatchdog()
        manualStop = true; state.stopRequested(); notifyStatus("Done, processing…")
        try { recognizer?.stopListening() ?: finishError(null, "Speech service stopped before finishing. Tap the microphone to retry, or type instead.") }
        catch (t: Throwable) { CrashLog.record("VOICE", "manual stopListening failed", t); finishError(null, "Speech could not finish. Check microphone permission and try again.") }
    }

    fun cancel() {
        assertMain(); generation++; active = false; cancelStartupWatchdog(); handler.removeCallbacksAndMessages(null)
        cleanupRecognizer(cancel = true); abandonFocus(); state.reset(); diag(null, "Cancelled.")
    }

    fun release() {
        assertMain(); generation++; active = false; cancelStartupWatchdog(); handler.removeCallbacksAndMessages(null)
        cleanupRecognizer(cancel = true); abandonFocus(); state.reset(); diag(null, "Released.")
    }

    private fun createAndListen(token: Int) {
        cleanupRecognizer(cancel = true)
        if (!active || token != generation) return
        val service = RecognitionServiceResolver.create(appContext)
        recognizer = service
        service.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Preference only; provider may still use network.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        notifyStatus("Starting Android speech recognition; offline preference is not a guarantee…")
        diag(null, "Starting (locale=$currentLanguage).")
        ready = false
        service.startListening(intent)
        val watchdogToken = token
        val watchdog = Runnable {
            if (active && generation == watchdogToken && !ready) startTimedOut()
        }
        startupWatchdog?.let(handler::removeCallbacks)
        startupWatchdog = watchdog
        handler.postDelayed(watchdog, START_TIMEOUT_MS)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (active) { ready = true; cancelStartupWatchdog(); state.start(); notifyStatus("Listening… Speak a short request."); diag(null, "Listening ($currentLanguage).") } }
        override fun onBeginningOfSpeech() { if (active) { ready = true; cancelStartupWatchdog(); diag(null, "Speech started.") } }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { state.processing(); notifyStatus("Done, processing…"); diag(null, "Speech ended; awaiting result.") }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(results: Bundle?) {
            if (!active) return
            try { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) { lastPartial = text.take(2000); state.partial(lastPartial); onPartial(lastPartial); diag(null, "Partial result received (text omitted).") }
            } catch (t: Throwable) { CrashLog.record("VOICE", "partial result callback failed", t); finishError(null, "Speech text could not be read. Check app microphone permission and try again.") }
        }
        override fun onResults(results: Bundle?) {
            if (!active) return
            try { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); finishResult(text) }
            catch (t: Throwable) { CrashLog.record("VOICE", "final result callback failed", t); finishError(null, "Speech result could not be read. Try again or type instead.") }
        }
        override fun onError(error: Int) { if (active) { cancelStartupWatchdog(); handleError(error) } }
    }

    private fun handleError(code: Int) {
        if (lastPartial.isNotBlank()) { finishResult(""); return }
        if (SpeechRetryPolicy.shouldRetry(code, retryUsed, cancelled = manualStop)) {
            retryUsed = true; val token = generation
            notifyStatus("Android speech service is busy; resetting it and retrying once in 400 ms…")
            diag(code, "Retry scheduled after Android error $code.")
            cleanupRecognizer(cancel = true); abandonFocus()
            handler.postDelayed({ if (active && generation == token) {
                try { if (!requestFocus()) { finishWithFallback(code, "Audio focus could not be reacquired for the retry. Stop other playback and try again."); return@postDelayed }; createAndListen(token) }
                catch (t: Throwable) { CrashLog.record("VOICE", "speech retry failed", t); retryFailed(code) }
            } }, SpeechRetryPolicy.RETRY_DELAY_MS)
        } else if (retryUsed && (code == 5 || code == 8)) retryFailed(code)
        else finishWithFallback(code, explain(code))
    }

    private fun retryFailed(code: Int) {
        val message = "Android speech service remained busy after one retry (error $code). Close other voice apps and try again, or type your request."
        finishWithFallback(code, message)
    }

    private fun startTimedOut() {
        val msg = "The speech service did not start listening in 5 seconds. Another voice app may be holding the microphone."
        diag(SpeechRecognizer.ERROR_CLIENT, "Start watchdog fired; no onReadyForSpeech.")
        finishWithFallback(SpeechRecognizer.ERROR_CLIENT, msg)
    }

    private fun finishWithFallback(code: Int?, message: String) {
        finishError(code, message)
        if (SpeechRetryPolicy.shouldFallbackToVosk(engineMode, retryFailed = true)) try { onRetryFailed?.invoke(code ?: SpeechRecognizer.ERROR_CLIENT, message) }
        catch (t: Throwable) { CrashLog.record("VOICE", "retry-failure callback failed", t) }
    }

    private fun explain(code: Int): String = when (code) {
        1 -> "Speech service timed out. Check that a recognition service is enabled and try again."
        2 -> "Speech service reported a network problem. Check its network settings, install an offline voice pack, or use Offline mode; offline preference is not guaranteed."
        3 -> "Microphone audio could not be read. Check microphone access in app settings and try again."
        4, 11 -> "Android speech service disconnected. Enable a speech service in phone settings, then retry."
        5, 8 -> "Android speech service could not start. Close other voice apps and retry."
        6 -> "No speech was detected. Tap the microphone and speak after it says Listening."
        7 -> "No words were recognized. Try a short sentence closer to the microphone, or type instead."
        9 -> "Microphone permission is missing. Allow Microphone in Android app settings, then retry."
        10 -> "Speech service rejected the request. Enable or update a speech service in phone settings, then retry."
        12, 13 -> "No supported language is available. Install an English voice in speech settings or use Offline mode."
        else -> "Android speech recognition stopped (error $code). Check microphone permission and speech-service settings, then retry."
    }

    private fun finishResult(text: String) {
        cancelStartupWatchdog()
        val result = state.result(text.take(2000)); active = false; cleanupRecognizer(cancel = false); abandonFocus()
        if (result.finalText.isNotBlank()) { notifyStatus(result.message); diag(null, "Transcript ready (contents omitted)."); try { onFinal(result.finalText) } catch (t: Throwable) { CrashLog.record("VOICE", "final transcript callback failed", t); notifyStatus("Could not place speech text in the message. Try again or type it.") } }
        else finishError(null, result.message)
    }
    private fun finishError(code: Int?, message: String) {
        active = false; cancelStartupWatchdog(); handler.removeCallbacksAndMessages(null); cleanupRecognizer(cancel = true); abandonFocus()
        state.error(message); SpeechDiagnostics.error(message); diag(code, "Error: $message"); notifyStatus(message)
    }
    private fun cancelStartupWatchdog() { startupWatchdog?.let(handler::removeCallbacks); startupWatchdog = null }
    private fun requestFocus(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { }.build()
            focusRequest = request; audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else @Suppress("DEPRECATION") (audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    } catch (t: Throwable) { CrashLog.record("VOICE", "audio focus request failed", t); false }
    private fun abandonFocus() { try { if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null) } catch (t: Throwable) { CrashLog.record("VOICE", "audio focus abandon failed", t) }; focusRequest = null }
    private fun cleanupRecognizer(cancel: Boolean) { val old = recognizer; recognizer = null; if (old != null) { if (cancel) try { old.cancel() } catch (t: Throwable) { CrashLog.record("VOICE", "recognizer cancel failed", t) }; try { old.destroy() } catch (t: Throwable) { CrashLog.record("VOICE", "recognizer destroy failed", t) } } }
    private fun notifyStatus(value: String) { SpeechDiagnostics.status(value); try { onStatus(value.take(500)) } catch (t: Throwable) { CrashLog.record("VOICE", "speech status callback failed", t) } }
    private fun diag(code: Int?, status: String) = SpeechDiagnostics.record("ANDROID", code, status)
    private fun assertMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Speech controller must be called on main thread" } }

    companion object { private const val START_TIMEOUT_MS = 5_000L }
}
