package com.cue.offlinebrain.ui

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.cue.offlinebrain.alarm.AlarmScheduler
import com.cue.offlinebrain.alarm.SystemClockAlarmMirror
import com.cue.offlinebrain.alarm.CronSchedule
import com.cue.offlinebrain.MainActivity
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.accessibility.MacroCaptureSession
import com.cue.offlinebrain.accessibility.AccessibilityStatus
import com.cue.offlinebrain.accessibility.DeviceActions
import com.cue.offlinebrain.data.AlarmEntity
import com.cue.offlinebrain.data.AutomationRuleEntity
import com.cue.offlinebrain.data.ClipboardEntryEntity
import com.cue.offlinebrain.data.EncryptedBackup
import com.cue.offlinebrain.data.EncryptedBackupPreview
import com.cue.offlinebrain.data.MacroEntity
import com.cue.offlinebrain.data.MacroRunPolicy
import com.cue.offlinebrain.data.MacroStepParser
import com.cue.offlinebrain.data.MacroStepDraft
import com.cue.offlinebrain.data.MacroStepEditorModel
import com.cue.offlinebrain.data.MacroStepExecutor
import com.cue.offlinebrain.data.TypedMacroStep
import com.cue.offlinebrain.data.TypedMacroStepRecord
import com.cue.offlinebrain.data.TypedMacroSteps
import com.cue.offlinebrain.data.NotificationEntity
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.AppLockStateStore
import com.cue.offlinebrain.data.PinLockStore
import com.cue.offlinebrain.data.BrainRepository
import com.cue.offlinebrain.data.MessageEntity
import com.cue.offlinebrain.data.BrainReply
import com.cue.offlinebrain.data.BrainReplyCodec
import com.cue.offlinebrain.data.brainReplyFromLegacyText
import com.cue.offlinebrain.data.NoteEntity
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.diagnostics.DiagnosticAlarmReceiver
import com.cue.offlinebrain.diagnostics.DiagnosticResult
import com.cue.offlinebrain.diagnostics.DiagnosticsRunner
import com.cue.offlinebrain.notifications.NotificationAccessState
import com.cue.offlinebrain.notifications.NotificationListenerKeepAliveService
import com.cue.offlinebrain.voice.VoskSpeechController
import com.cue.offlinebrain.voice.VoiceSpeechOutput
import com.cue.offlinebrain.voice.AndroidSpeechInputController
import com.cue.offlinebrain.voice.SpeechDiagnostics
import com.cue.offlinebrain.voice.SpeechSessionUiState
import com.cue.offlinebrain.voice.VoiceEngineMode
import com.cue.offlinebrain.worker.FeatureWorkScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.first

data class MacroRunFailure(val macro: MacroEntity, val stepIndex: Int, val stepCount: Int, val message: String)
data class NotificationDeleteUndo(val ids: List<Long>, val deletedAt: Long, val expiresAt: Long)

class BrainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OfflineBrainApplication
    private val repository = app.repository
    private val diagnosticsRunner = DiagnosticsRunner(application)
    private val voiceController by lazy { VoskSpeechController(application) }
    private val androidSpeechController by lazy { AndroidSpeechInputController(application) }
    private val _temporaryMacroMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = combine(repository.messages.map { it.asReversed() }, _temporaryMacroMessages) { saved, temporary ->
        (saved + temporary).takeLast(120)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notes: StateFlow<List<NoteEntity>> = repository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val alarms: StateFlow<List<AlarmEntity>> = app.database.dao().observeAlarms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notificationHistory: StateFlow<List<NotificationEntity>> = app.database.dao().observeNotificationHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notificationCategoryCounts = app.database.dao().observeNotificationCategoryCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _notificationDeleteUndo = MutableStateFlow<NotificationDeleteUndo?>(null)
    val notificationDeleteUndo: StateFlow<NotificationDeleteUndo?> = _notificationDeleteUndo.asStateFlow()
    val clipboardHistory: StateFlow<List<ClipboardEntryEntity>> = app.database.dao().observeClipboardHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val automationRules: StateFlow<List<AutomationRuleEntity>> = app.database.dao().observeAutomationRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val macros: StateFlow<List<MacroEntity>> = app.database.dao().observeMacros().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val _settingsReady = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = _settingsReady.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val _step = MutableStateFlow<String?>(null)
    val step = _step.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    private val _securityWorking = MutableStateFlow(false)
    val securityWorking = _securityWorking.asStateFlow()
    private val _diagnostics = MutableStateFlow<List<DiagnosticResult>>(emptyList())
    val diagnostics = _diagnostics.asStateFlow()
    private val _diagnosticsRunning = MutableStateFlow(false)
    val diagnosticsRunning = _diagnosticsRunning.asStateFlow()
    private val _voiceStatus = MutableStateFlow<String?>(null)
    val voiceStatus = _voiceStatus.asStateFlow()
    private val _voiceProgress = MutableStateFlow<Int?>(null)
    val voiceProgress = _voiceProgress.asStateFlow()
    private val _voiceRecording = MutableStateFlow(false)
    val voiceRecording = _voiceRecording.asStateFlow()
    private val _voiceTranscript = MutableStateFlow<String?>(null)
    val voiceTranscript = _voiceTranscript.asStateFlow()
    private val _speechSession = MutableStateFlow(SpeechSessionUiState())
    val speechSession = _speechSession.asStateFlow()
    private val _macroRecording = MutableStateFlow(false)
    val macroRecording = _macroRecording.asStateFlow()
    val recordedMacroSteps = MacroCaptureSession.steps
    private val _recordingMacroName = MutableStateFlow<String?>(null)
    val recordingMacroName = _recordingMacroName.asStateFlow()
    private val _macroRunFailure = MutableStateFlow<MacroRunFailure?>(null)
    val macroRunFailure = _macroRunFailure.asStateFlow()
    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled = _accessibilityEnabled.asStateFlow()
    private var activeJob: Job? = null
    private var voiceJob: Job? = null
    private var voiceStop: CompletableDeferred<Unit>? = null
    private var diagnosticJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.settings.settings.collect { current ->
                    if (!AppLockStateStore.isInitialized(getApplication())) AppLockStateStore.setEnabled(getApplication(), current.appLockEnabled)
                    val mirrorEnabled = AppLockStateStore.isEnabled(getApplication())
                    val lockEnabled = current.appLockEnabled || mirrorEnabled
                    if (lockEnabled && !mirrorEnabled) AppLockStateStore.setEnabled(getApplication(), true)
                    _settings.value = current.copy(
                        appLockEnabled = lockEnabled,
                        appLockTimeoutSeconds = AppLockStateStore.timeoutSeconds(getApplication())
                    )
                    _settingsReady.value = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("SETTINGS", "settings observer failed", error)
                _settingsReady.value = true
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.seedFaqs() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CrashLog.record("FAQ", "FAQ seeding failed", error) }
        }
    }

    fun send(text: String, speakReply: Boolean = false) {
        val value = text.trim()
        if (value.isEmpty() || _working.value) return
        val recordingAtSend = _macroRecording.value
        if (recordingAtSend) {
            if (MacroCaptureSession.steps.value.size < 50) MacroCaptureSession.append(value.take(500))
            else _toast.value = "The 50-step recording limit is reached. Stop and save or discard this macro."
            addTemporaryMacroMessage("user", value)
        } else viewModelScope.launch { app.database.dao().addMessage(MessageEntity(role = "user", text = value)) }
        _working.value = true
        _step.value = stepFor(value)
        activeJob = viewModelScope.launch {
            var answerForSpeech: String? = null
            try {
                val answer = withContext(Dispatchers.IO) { repository.handle(value, onStep = { progress -> _step.value = progress }) }
                if (recordingAtSend) { if (_macroRecording.value) addTemporaryMacroMessage("assistant", answer.plainText) }
                else app.database.dao().addMessage(MessageEntity(role = "assistant", text = answer.plainText, replyJson = BrainReplyCodec.encode(answer)))
                if (speakReply) answerForSpeech = answer.plainText
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val answer = BrainReply.Text("I couldn't complete that: ${e.message ?: "please check your connection or input"}.")
                if (recordingAtSend) { if (_macroRecording.value) addTemporaryMacroMessage("assistant", answer.plainText) }
                else app.database.dao().addMessage(MessageEntity(role = "assistant", text = answer.plainText, replyJson = BrainReplyCodec.encode(answer)))
                if (speakReply) answerForSpeech = answer.plainText
            } finally {
                _working.value = false
                _step.value = null
            }
            answerForSpeech?.let { VoiceSpeechOutput.speakOnce(getApplication(), it) }
        }
    }

    private fun addTemporaryMacroMessage(role: String, text: String) {
        val transient = MessageEntity(id = -System.nanoTime(), role = role, text = text)
        _temporaryMacroMessages.value = (_temporaryMacroMessages.value + transient).takeLast(60)
    }

    fun submitPdf(uri: Uri) {
        if (_working.value) return
        _working.value = true
        _step.value = "Opening the selected PDF"
        viewModelScope.launch { app.database.dao().addMessage(MessageEntity(role = "user", text = "Summarize the selected PDF")) }
        activeJob = viewModelScope.launch {
            try {
                val summary = repository.summarizePdf(uri) { progress -> _step.value = progress }
                val reply = brainReplyFromLegacyText(summary)
                app.database.dao().addMessage(MessageEntity(role = "assistant", text = reply.plainText, replyJson = BrainReplyCodec.encode(reply)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("PDF", "PDF summary failed", error)
                val reply = BrainReply.Text("I couldn't summarize that PDF: ${error.message ?: error.javaClass.simpleName}")
                app.database.dao().addMessage(MessageEntity(role = "assistant", text = reply.plainText, replyJson = BrainReplyCodec.encode(reply)))
            } finally {
                _working.value = false
                _step.value = null
            }
        }
    }

    fun stop() {
        activeJob?.cancel(); activeJob = null
        viewModelScope.launch { app.database.dao().addMessage(MessageEntity(role = "assistant", text = "Stopped. No more actions will run for that request.")) }
    }

    fun refreshAccessibilityStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _accessibilityEnabled.value = AccessibilityStatus.isBrainServiceEnabled(getApplication())
        }
    }

    fun startAndroidVoice(mode: VoiceEngineMode = _settings.value.voiceEngineMode, diagnosticOnly: Boolean = false) {
        if (_working.value || voiceJob?.isActive == true || _speechSession.value.listening || _speechSession.value.processing) return
        _voiceTranscript.value = null
        _voiceProgress.value = null
        _voiceStatus.value = "Starting Android speech recognition…"
        _speechSession.value = SpeechSessionUiState(engine = "Android", status = _voiceStatus.value.orEmpty(), processing = true, diagnosticOnly = diagnosticOnly)
        androidSpeechController.start(
            onPartial = { words ->
                _speechSession.value = _speechSession.value.copy(engine = "Android", status = "Listening…", listening = true, processing = false, recognizedText = words.take(2_000))
            },
            onFinal = { words ->
                val transcript = words.trim().take(2_000)
                val status = if (diagnosticOnly) "Microphone test complete." else "Speech ready. Review or edit the words, then tap Send."
                _speechSession.value = _speechSession.value.copy(engine = "Android", status = status, listening = false, processing = false, recognizedText = transcript, errorCode = null)
                _voiceStatus.value = status
                if (diagnosticOnly) SpeechDiagnostics.record("ANDROID", null, "Microphone test completed; transcript is shown in the Diagnostics screen.")
                else if (transcript.isNotBlank()) _voiceTranscript.value = transcript
            },
            onStatus = { status ->
                val lastEvent = SpeechDiagnostics.events.lastOrNull()
                _voiceStatus.value = status
                _speechSession.value = _speechSession.value.copy(
                    engine = "Android",
                    status = status,
                    listening = status.startsWith("Listening", true) && !status.startsWith("Listening stopped", true),
                    processing = status.startsWith("Starting", true) || status.startsWith("Done, processing", true) || status.startsWith("Android speech service is busy", true),
                    errorCode = lastEvent?.errorCode,
                    diagnosticOnly = diagnosticOnly
                )
            },
            mode = mode,
            onRetryFailed = { code, message ->
                _speechSession.value = _speechSession.value.copy(engine = "Offline (Vosk)", status = "Using offline engine. ${message.take(160)}", listening = false, processing = true, errorCode = code, diagnosticOnly = diagnosticOnly)
                _voiceStatus.value = "Using offline engine. Preparing the English model…"
                startVoice(diagnosticOnly = diagnosticOnly)
            }
        )
    }

    fun startVoice(diagnosticOnly: Boolean = false) {
        if (_working.value || voiceJob?.isActive == true) return
        _voiceTranscript.value = null
        _voiceStatus.value = "Preparing offline English speech…"
        _voiceProgress.value = 0
        _speechSession.value = SpeechSessionUiState(engine = "Offline (Vosk)", status = _voiceStatus.value.orEmpty(), processing = true, diagnosticOnly = diagnosticOnly)
        voiceJob = viewModelScope.launch {
            try {
                val modelDir = voiceController.ensureModel { percent -> _voiceProgress.value = percent }
                _voiceProgress.value = null
                val stop = CompletableDeferred<Unit>()
                voiceStop = stop
                val transcript = voiceController.listen(modelDir, stop) {
                    _voiceRecording.value = true
                    _voiceStatus.value = "Listening. Tap the microphone again when you’re done."
                    _speechSession.value = _speechSession.value.copy(engine = "Offline (Vosk)", status = _voiceStatus.value.orEmpty(), listening = true, processing = false)
                }
                _voiceRecording.value = false
                voiceStop = null
                if (transcript.isBlank()) {
                    _voiceStatus.value = "No speech was recognized. Tap the microphone to try again."
                    _speechSession.value = _speechSession.value.copy(status = _voiceStatus.value.orEmpty(), listening = false, processing = false)
                    CrashLog.record("VOICE", "recording ended with no recognized words")
                } else if (diagnosticOnly) {
                    _voiceStatus.value = "Microphone test complete."
                    _speechSession.value = _speechSession.value.copy(engine = "Offline (Vosk)", status = _voiceStatus.value.orEmpty(), listening = false, processing = false, recognizedText = transcript.take(2_000))
                    SpeechDiagnostics.record("VOSK", null, "Microphone test completed; transcript is shown in the Diagnostics screen.")
                } else {
                    val preferences = app.settings.settings.first()
                    CrashLog.record("VOICE", "offline recognition produced a transcript; autoSend=${preferences.autoSendVoiceTranscript}")
                    if (preferences.autoSendVoiceTranscript) {
                        _voiceStatus.value = null
                        _speechSession.value = _speechSession.value.copy(engine = "Offline (Vosk)", status = "Speech recognized. Sending…", listening = false, processing = false, recognizedText = transcript.take(2_000))
                        send(transcript, speakReply = true)
                    } else {
                        _voiceTranscript.value = transcript.take(2_000)
                        _voiceStatus.value = "Speech ready. Review or edit the words, then tap Send."
                        _speechSession.value = _speechSession.value.copy(engine = "Offline (Vosk)", status = _voiceStatus.value.orEmpty(), listening = false, processing = false, recognizedText = transcript.take(2_000))
                    }
                }
            } catch (cancelled: CancellationException) {
                _voiceStatus.value = "Voice input stopped."
                _speechSession.value = _speechSession.value.copy(status = _voiceStatus.value.orEmpty(), listening = false, processing = false)
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("VOICE", "voice input failed", error)
                _voiceStatus.value = "Voice input failed: ${error.message ?: error.javaClass.simpleName}"
                _speechSession.value = _speechSession.value.copy(status = _voiceStatus.value.orEmpty(), listening = false, processing = false)
                _voiceProgress.value = null
            } finally {
                _voiceRecording.value = false
                _voiceProgress.value = null
                voiceStop = null
            }
        }
    }

    fun toggleVoice() {
        if (voiceJob?.isActive == true) stopVoice()
        else if (_settings.value.voiceEngineMode == VoiceEngineMode.OFFLINE) {
            _voiceStatus.value = null
            startVoice()
        } else startAndroidVoice(_settings.value.voiceEngineMode)
    }

    fun runVoiceDiagnostic() {
        if (_settings.value.voiceEngineMode == VoiceEngineMode.OFFLINE) startVoice(diagnosticOnly = true)
        else startAndroidVoice(_settings.value.voiceEngineMode, diagnosticOnly = true)
    }

    fun stopVoice() {
        if (voiceJob?.isActive != true) {
            androidSpeechController.stop()
            _speechSession.value = _speechSession.value.copy(status = "Done, processing…", listening = false, processing = true)
            return
        }
        val stop = voiceStop
        if (_voiceRecording.value && stop != null) {
            stop.complete(Unit)
            _voiceStatus.value = "Finishing recognition…"
        } else {
            voiceJob?.cancel()
            voiceJob = null
            _voiceProgress.value = null
            _voiceStatus.value = "Voice download stopped. Tap the microphone to retry."
        }
    }

    fun clearVoiceStatus() { if (!_voiceRecording.value) _voiceStatus.value = null }
    fun clearVoiceTranscript() { _voiceTranscript.value = null }

    fun runDiagnostics() {
        if (_diagnosticsRunning.value) return
        _diagnosticsRunning.value = true
        diagnosticJob = viewModelScope.launch {
            try { diagnosticsRunner.runAll { result -> upsertDiagnostic(result) } }
            finally { _diagnosticsRunning.value = false }
        }
    }

    private fun upsertDiagnostic(result: DiagnosticResult) {
        val current = _diagnostics.value.toMutableList()
        val index = current.indexOfFirst { it.name == result.name }
        if (index >= 0) current[index] = result else current.add(result)
        _diagnostics.value = current
    }

    fun scheduleTestAlarmIn30Seconds() {
        try {
            val manager = getApplication<Application>().getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: throw IllegalStateException("AlarmManager unavailable")
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms())
                throw SecurityException("Android exact-alarm access is off")
            val show = PendingIntent.getActivity(getApplication(), QUICK_ALARM_REQUEST xor 0x1100,
                Intent(getApplication(), MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val fire = PendingIntent.getBroadcast(getApplication(), QUICK_ALARM_REQUEST,
                Intent(getApplication(), DiagnosticAlarmReceiver::class.java).setAction(DiagnosticAlarmReceiver.ACTION_TEST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = System.currentTimeMillis() + 30_000L
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
            _toast.value = "Test alarm scheduled for ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}. It will ring and speak; use Stop on its notification."
            CrashLog.record("DIAGNOSTIC", "30-second test alarm scheduled")
        } catch (error: Exception) {
            CrashLog.record("DIAGNOSTIC", "30-second test alarm scheduling failed", error)
            _toast.value = "Test alarm was not scheduled: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun addOneTimeAlarm(label: String, triggerAt: Long) {
        if (triggerAt <= System.currentTimeMillis()) { _toast.value = "Choose a time in the future."; return }
        viewModelScope.launch {
            try {
                val alarmLabel = label.trim().ifBlank { "Reminder" }.take(180)
                val id = withContext(Dispatchers.IO) {
                    val row = AlarmEntity(label = alarmLabel, triggerAt = triggerAt)
                    val createdId = app.database.dao().addAlarm(row)
                    if (!AlarmScheduler.schedule(app, row.copy(id = createdId))) {
                        app.database.dao().deleteAlarm(createdId)
                        throw IllegalStateException("Android did not accept the alarm schedule")
                    }
                    createdId
                }
                val prefs = app.settings.settings.first()
                val mirror = if (prefs.mirrorAlarmsToSystemClock) withContext(Dispatchers.Main) {
                    SystemClockAlarmMirror.addOneTime(app, triggerAt, alarmLabel)
                } else ""
                _toast.value = "One-time reminder scheduled. It disappears from this list; Stop removes it after it rings.$mirror"
                CrashLog.record("ALARM", "one-time reminder created id=$id")
            } catch (error: Exception) {
                CrashLog.record("ALARM", "one-time reminder creation failed", error)
                _toast.value = "Reminder was not scheduled: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    fun setting(block: suspend com.cue.offlinebrain.data.SettingsStore.() -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { app.settings.block() } }
            catch (error: Exception) { CrashLog.record("SETTINGS", "settings write failed", error); _toast.value = "Settings could not be saved: ${error.message ?: "secure storage error"}" }
        }
    }
    fun markMacroTutorialSeen() = setting { setMacroTutorialSeen() }
    fun updateAlarm(alarm: AlarmEntity, label: String, triggerAt: Long) {
        if (triggerAt <= System.currentTimeMillis()) { _toast.value = "Choose a time in the future."; return }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val dao = app.database.dao()
                    val previous = dao.getAlarm(alarm.id) ?: throw IllegalStateException("Alarm no longer exists")
                    val updated = alarm.copy(label = label.trim().ifBlank { "Reminder" }.take(180), triggerAt = triggerAt)
                    AlarmScheduler.cancel(app, updated.id)
                    dao.updateAlarm(updated.id, updated.label, updated.triggerAt, updated.cron, updated.enabled)
                    if (updated.enabled && !AlarmScheduler.schedule(app, updated)) {
                        dao.updateAlarm(previous.id, previous.label, previous.triggerAt, previous.cron, previous.enabled)
                        AlarmScheduler.cancel(app, updated.id)
                        if (previous.enabled) AlarmScheduler.schedule(app, previous)
                        throw IllegalStateException("Android did not accept the alarm schedule")
                    }
                }
                _toast.value = "Alarm updated."
            }.onFailure { CrashLog.record("ALARM", "alarm edit failed id=${alarm.id}", it); _toast.value = "Alarm was not updated: ${it.message ?: "schedule failed"}" }
        }
    }

    fun setAlarmEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val alarm = app.database.dao().getAlarm(id) ?: return@withContext false
                    val updated = if (enabled) alarm.copy(enabled = true, triggerAt = nextAlarmTime(alarm)) else alarm.copy(enabled = false)
                    app.database.dao().updateAlarm(updated.id, updated.label, updated.triggerAt, updated.cron, updated.enabled)
                    AlarmScheduler.cancel(app, id)
                    if (enabled && !AlarmScheduler.schedule(app, updated)) {
                        app.database.dao().setAlarmEnabled(id, false)
                        false
                    } else true
                }
                if (!result) _toast.value = "Alarm couldn't be enabled. Check exact-alarm access in Android settings."
            } catch (error: Exception) { CrashLog.record("ALARM", "alarm enable change failed id=$id", error); _toast.value = "Alarm change failed: ${error.message ?: "system error"}" }
        }
    }

    fun deleteAlarm(id: Long, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dao = app.database.dao()
                    val previous = dao.getAlarm(id)
                    if (previous != null) {
                        AlarmScheduler.cancel(app, id)
                        try { dao.deleteAlarm(id) }
                        catch (error: Throwable) {
                            if (previous.enabled) runCatching { AlarmScheduler.schedule(app, previous) }
                            throw error
                        }
                    }
                }
            }
            result.onFailure { CrashLog.record("ALARM", "alarm deletion failed id=$id", it); _toast.value = "Alarm couldn't be deleted." }
            onComplete(result.isSuccess)
        }
    }

    fun addNote(text: String) { if (text.isNotBlank()) viewModelScope.launch { app.database.dao().addNote(NoteEntity(text = text.trim().take(1000))) } }
    fun setNoteDone(id: Long, done: Boolean) { viewModelScope.launch { app.database.dao().setNoteDone(id, done) } }
    fun deleteNote(id: Long) { viewModelScope.launch { app.database.dao().deleteNote(id) } }
    fun clearClipboardHistory() { viewModelScope.launch(Dispatchers.IO) { runCatching { app.database.dao().clearAllClipboardHistory() }.onSuccess { _toast.value = "Clipboard history cleared." }.onFailure { _toast.value = "Clipboard history could not be cleared." } } }
    fun saveAutomationRule(trigger: String, action: String) {
        val cleanTrigger = trigger.trim().take(120)
        val cleanAction = action.trim().take(500)
        if (cleanTrigger.length < 2 || cleanAction.isBlank()) { _toast.value = "Add a trigger phrase and the command it should run."; return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = app.database.dao()
                dao.deleteAutomationRulesByTrigger(cleanTrigger)
                dao.saveAutomationRule(AutomationRuleEntity(triggerPhrase = cleanTrigger, actionText = cleanAction))
            }.onSuccess { _toast.value = "Automation rule saved." }
                .onFailure { CrashLog.record("AUTOMATION", "rule save failed", it); _toast.value = "Rule couldn't be saved: ${it.message ?: "database error"}" }
        }
    }
    fun deleteAutomationRule(id: Long) { viewModelScope.launch(Dispatchers.IO) { runCatching { app.database.dao().deleteAutomationRule(id) }.onFailure { _toast.value = "Rule could not be deleted." } } }
    /** Legacy capture/request strings are migrated to typed Chat-request records on the next save. */
    fun saveMacro(name: String, rawSteps: String) {
        val records = try { TypedMacroSteps.decodeRecords(rawSteps) }
        catch (error: Exception) { _toast.value = "Macro steps could not be read. Reopen and review them."; CrashLog.record("AUTOMATION", "legacy macro decode failed", error); return }
        persistTypedMacro(name, records, null)
    }
    fun saveTypedMacro(name: String, drafts: List<MacroStepDraft>) = persistTypedMacro(
        name, drafts.map { TypedMacroStepRecord(it.id, it.step) }, null
    )
    fun updateTypedMacro(id: Long, name: String, drafts: List<MacroStepDraft>) = persistTypedMacro(
        name, drafts.map { TypedMacroStepRecord(it.id, it.step) }, id
    )
    fun updateMacro(id: Long, name: String, rawSteps: String) {
        val records = try { TypedMacroSteps.decodeRecords(rawSteps) }
        catch (error: Exception) { _toast.value = "Macro steps could not be read. Reopen and review them."; CrashLog.record("AUTOMATION", "legacy macro decode failed id=$id", error); return }
        persistTypedMacro(name, records, id)
    }
    private fun persistTypedMacro(name: String, records: List<TypedMacroStepRecord>, editedId: Long?) {
        val cleanName = name.trim().take(80)
        val drafts = records.map { MacroStepDraft(it.id, it.step) }
        if (cleanName.isBlank()) { _toast.value = "Add a macro name."; return }
        val localError = MacroStepEditorModel.validate(drafts, macros.value, editedId)
        if (localError != null) {
            _toast.value = localError
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = app.database.dao()
                val existingMacros = dao.allMacrosForBackup()
                val error = MacroStepEditorModel.validate(drafts, existingMacros, editedId)
                if (error != null) throw IllegalArgumentException(error)
                val encoded = TypedMacroSteps.encodeRecords(records)
                if (editedId == null) {
                    if (dao.macroByName(cleanName) != null) throw IllegalArgumentException("Another macro already uses that name.")
                    dao.saveMacro(MacroEntity(name = cleanName, steps = encoded))
                } else {
                    val existing = dao.macroById(editedId) ?: throw IllegalStateException("The saved macro no longer exists.")
                    val duplicate = dao.macroByName(cleanName)
                    if (duplicate != null && duplicate.id != editedId) throw IllegalArgumentException("Another macro already uses that name.")
                    val updated = dao.updateMacro(existing.copy(name = cleanName, steps = encoded, updatedAt = System.currentTimeMillis()))
                    if (updated != 1) throw IllegalStateException("The saved macro could not be updated.")
                }
                _toast.value = if (editedId == null) "Macro saved with ${records.size} typed steps." else "Macro updated with ${records.size} typed steps."
                CrashLog.record("AUTOMATION", "typed macro persisted steps=${records.size} updated=${editedId != null}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("AUTOMATION", "typed macro persistence failed (${error.javaClass.simpleName})", error)
                _toast.value = error.message?.take(140) ?: "Macro could not be saved."
            }
        }
    }
    fun deleteMacro(id: Long) { viewModelScope.launch(Dispatchers.IO) { runCatching { app.database.dao().deleteMacro(id) }.onFailure { _toast.value = "Macro could not be deleted." } } }

    fun startMacroRecording(name: String) {
        val cleanName = name.trim().take(80)
        if (cleanName.isBlank()) { _toast.value = "Name the macro before recording."; return }
        if (_working.value || _macroRecording.value) { _toast.value = "Stop the current task or recording first."; return }
        MacroCaptureSession.start()
        _temporaryMacroMessages.value = emptyList()
        _recordingMacroName.value = cleanName
        _macroRecording.value = true
        _toast.value = if (DeviceActions.available()) "Recording screen actions in memory. Return here and tap Stop and save. Never record passwords or private codes."
        else "Recording Chat requests in memory. Enable Accessibility to capture taps and typing in other apps. Never record passwords or private codes."
        CrashLog.record("AUTOMATION", "user started a visible command recording")
    }

    fun stopMacroRecording(save: Boolean = true) {
        if (!_macroRecording.value) return
        val name = _recordingMacroName.value.orEmpty()
        val steps = MacroCaptureSession.stopAndTake().take(50)
        _temporaryMacroMessages.value = emptyList()
        _macroRecording.value = false
        _recordingMacroName.value = null
        CrashLog.record("AUTOMATION", "user stopped command recording steps=${steps.size}")
        if (save && steps.isNotEmpty()) saveMacro(name, steps.joinToString("\n"))
        else _toast.value = if (save) "Nothing was recorded." else "Recording discarded."
    }

    fun runMacro(macro: MacroEntity, startAtStep: Int = 0) {
        val steps = try { TypedMacroSteps.decode(macro.steps) }
        catch (error: Exception) { CrashLog.record("AUTOMATION", "macro decode failed id=${macro.id}", error); _toast.value = "This macro's saved steps could not be read. Edit and resave it."; return }
        if (steps.isEmpty()) { _toast.value = "This macro has no usable steps."; return }
        if (_working.value) { _toast.value = "Stop the current task before starting a macro."; return }
        val firstStep = startAtStep.coerceIn(0, steps.lastIndex)
        _macroRunFailure.value = null
        _working.value = true
        activeJob = viewModelScope.launch {
            try {
                val executor = MacroStepExecutor(
                    findMacro = { id -> app.database.dao().macroById(id) },
                    runChatCommand = { command ->
                        app.database.dao().addMessage(MessageEntity(role = "user", text = "[${macro.name}] $command"))
                        val answer = withContext(Dispatchers.IO) { repository.handle(command) }
                        app.database.dao().addMessage(MessageEntity(role = "assistant", text = answer.plainText, replyJson = BrainReplyCodec.encode(answer)))
                        answer.plainText
                    },
                    speakText = { text -> VoiceSpeechOutput.speakOnce(getApplication(), text) },
                    onProgress = { progress -> _step.value = "${progress.macroName}: step ${progress.stepIndex + 1} of ${progress.stepCount}" },
                    onError = { error -> CrashLog.record("AUTOMATION", "typed macro step raised ${error.javaClass.simpleName}", error) }
                )
                val result = executor.run(macro, firstStep)
                if (!result.succeeded) {
                    val index = result.failedStepIndex ?: firstStep
                    _macroRunFailure.value = MacroRunFailure(macro, index, result.stepCount.coerceAtLeast(1), result.message ?: "Step failed.")
                    _toast.value = "Macro paused at step ${index + 1}. Later steps were not run. Review it, then retry."
                    return@launch
                }
                _toast.value = "Macro finished."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("AUTOMATION", "macro run failed id=${macro.id}", error)
                _macroRunFailure.value = MacroRunFailure(macro, firstStep, steps.size, (error.message ?: error.javaClass.simpleName).take(400))
                _toast.value = "Macro stopped after an error: ${error.message ?: error.javaClass.simpleName}"
            } finally { _working.value = false; _step.value = null }
        }
    }
    fun retryMacroFailure() { _macroRunFailure.value?.let { failure -> runMacro(failure.macro, failure.stepIndex) } }
    fun clearMacroFailure() { _macroRunFailure.value = null }

    fun setBackgroundRefreshEnabled(value: Boolean) = setPeriodicFeature("refresh", value)
    fun setAutoBackupEnabled(value: Boolean) = setPeriodicFeature("backup", value)
    fun setNotificationLiveCaptureEnabled(value: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.settings.setNotificationLiveCaptureEnabled(value) }
                .onSuccess {
                    if (value && NotificationAccessState.enabled(getApplication())) {
                        NotificationAccessState.requestOnDemandRebind(getApplication(), "capture-enabled")
                    }
                    _toast.value = if (value) "Live capture requested. Grant Android Notification Access if it is not already on." else "Live capture is off. Saved notification history was kept."
                }
                .onFailure { CrashLog.record("NOTIFICATIONS", "live capture setting failed", it); _toast.value = "Live capture setting could not be saved." }
        }
    }
    fun setNotificationKeepAliveEnabled(value: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.settings.setNotificationKeepAliveEnabled(value)
                FeatureWorkScheduler.configureNotificationListenerWatch(getApplication(), value)
                if (value) {
                    NotificationListenerKeepAliveService.start(getApplication())
                    NotificationAccessState.repair(getApplication())
                    _toast.value = "Visible listener keep-alive started; WorkManager will check about every 6 hours when Android allows."
                } else {
                    NotificationListenerKeepAliveService.stop(getApplication())
                    _toast.value = "Listener keep-alive stopped. Saved notification history remains." 
                }
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "listener keep-alive setting failed", error)
                _toast.value = "Listener keep-alive could not be changed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }
    fun setNotificationPolicy(allowlist: String, blocklist: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.settings.setNotificationPolicy(allowlist, blocklist) }
                .onSuccess { _toast.value = "Per-app notification filters saved. Blocked apps are never stored." }
                .onFailure { CrashLog.record("NOTIFICATIONS", "app filters could not be saved", it); _toast.value = "Notification filters could not be saved." }
        }
    }
    fun setNotificationAlertOptions(banners: Boolean, speakNew: Boolean, chatCards: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.settings.setNotificationInAppAlertsEnabled(banners)
                app.settings.setNotificationSpeakNewEnabled(speakNew)
                app.settings.setNotificationChatAlertsEnabled(chatCards)
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "alert options could not be saved", error)
                _toast.value = "Notification alert settings could not be saved."
            }
        }
    }
    fun setNotificationAlertMutes(packages: String, categories: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.settings.setNotificationMutedPackages(packages)
                app.settings.setNotificationMutedCategories(categories)
                _toast.value = "Alert mute lists saved. Notifications remain saved in history."
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "alert mute lists could not be saved", error)
                _toast.value = "Alert mute lists could not be saved."
            }
        }
    }
    fun setNotificationCategoryOverride(packageName: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.settings.setNotificationCategoryOverride(packageName, category)
                app.database.dao().updateNotificationCategoryForPackage(packageName, category)
                _toast.value = "Saved and new notifications from this app will use $category."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "category override could not be saved", error)
                _toast.value = "App category could not be saved."
            }
        }
    }
    fun markAllNotificationsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.database.dao().markAllNotificationsRead() }
                .onSuccess { _toast.value = "All saved notifications marked as read." }
                .onFailure { _toast.value = "Notifications could not be updated." }
        }
    }
    fun markAllNotificationsUnread() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.database.dao().markAllNotificationsUnread() }
                .onSuccess { _toast.value = "All saved notifications marked unread." }
                .onFailure { _toast.value = "Notifications could not be updated." }
        }
    }
    fun markNotificationRead(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.database.dao().markNotificationRead(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "single notification read state could not be saved", error)
                _toast.value = "This notification could not be marked as read."
            }
        }
    }
    fun markNotificationUnread(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try { app.database.dao().markNotificationUnread(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "single notification unread state could not be saved", error)
                _toast.value = "This notification could not be marked unread."
            }
        }
    }
    fun softDeleteNotifications(ids: List<Long>) {
        if (ids.isEmpty()) return
        val uniqueIds = ids.distinct().take(500)
        val deletedAt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.database.dao().softDeleteNotifications(uniqueIds, deletedAt)
                val undo = NotificationDeleteUndo(uniqueIds, deletedAt, deletedAt + 10_000L)
                _notificationDeleteUndo.value = undo
                _toast.value = "Moved ${uniqueIds.size} notification(s) to Recently deleted. Undo is available for 10 seconds."
                delay(10_000L)
                app.database.dao().purgeSoftDeletedNotifications(deletedAt)
                if (_notificationDeleteUndo.value == undo) _notificationDeleteUndo.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("NOTIFICATIONS", "soft delete/purge failed", error)
                _toast.value = "Notifications could not be deleted."
            }
        }
    }
    fun undoNotificationDelete() {
        val undo = _notificationDeleteUndo.value ?: return
        if (System.currentTimeMillis() > undo.expiresAt) {
            _notificationDeleteUndo.value = null
            _toast.value = "The 10-second undo window has expired."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.database.dao().undoSoftDeleteNotifications(undo.ids, undo.deletedAt) }
                .onSuccess {
                    if (_notificationDeleteUndo.value == undo) _notificationDeleteUndo.value = null
                    _toast.value = "Notification deletion undone."
                }
                .onFailure { CrashLog.record("NOTIFICATIONS", "soft-delete undo failed", it); _toast.value = "Could not undo deletion." }
        }
    }
    fun clearNotificationCategory(category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.database.dao().clearNotificationCategory(category) }
                .onSuccess { _toast.value = "Saved $category notifications deleted." }
                .onFailure { _toast.value = "That category could not be cleared." }
        }
    }
    fun clearNotificationHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.database.dao().clearAllNotifications() }
                .onSuccess { _toast.value = "Saved notification history deleted." }
                .onFailure { _toast.value = "Notification history could not be cleared." }
        }
    }
    fun setMirrorAlarmsToSystemClock(value: Boolean) {
        setting { setMirrorAlarmsToSystemClock(value) }
    }
    private fun setPeriodicFeature(which: String, value: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (which == "refresh") app.settings.setBackgroundRefreshEnabled(value) else app.settings.setAutoBackupEnabled(value)
                val prefs = app.settings.settings.first()
                FeatureWorkScheduler.configure(getApplication(), prefs.backgroundRefreshEnabled, prefs.autoBackupEnabled)
                _toast.value = if (value) "Scheduled work enabled. Android runs it when its constraints allow." else "Scheduled work paused."
            } catch (error: Exception) {
                CrashLog.record("WORK", "periodic work setting failed", error)
                _toast.value = "Scheduled work setting could not be saved."
            }
        }
    }

    fun saveAppPin(pin: String, confirmation: String) {
        if (pin != confirmation) { _toast.value = "The two PIN entries do not match."; return }
        if (!pin.matches(Regex("[0-9]{6,12}"))) { _toast.value = "Use a PIN with 6 to 12 digits."; return }
        if (_securityWorking.value) return
        _securityWorking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PinLockStore.setPin(getApplication(), pin)
                app.settings.setAppLockEnabled(true)
                _toast.value = "PIN saved and app lock enabled on this device."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("SECURITY", "app PIN setup failed", error)
                _toast.value = "App lock could not be enabled: ${error.message ?: "secure storage failed"}"
            } finally { _securityWorking.value = false }
        }
    }

    fun enableAppLockWithExistingPin(pin: String, onVerified: (Boolean) -> Unit) {
        if (_securityWorking.value) return
        _securityWorking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var verified = false
            try {
                verified = PinLockStore.verify(getApplication(), pin)
                if (verified) app.settings.setAppLockEnabled(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("SECURITY", "existing PIN could not enable app lock", error)
                _toast.value = "Could not enable app lock. Check secure storage and try again."
            } finally {
                _securityWorking.value = false
                withContext(Dispatchers.Main.immediate) { onVerified(verified) }
            }
        }
    }

    fun changeAppPin(currentPin: String, newPin: String, confirmation: String) {
        if (newPin != confirmation) { _toast.value = "The two new PIN entries do not match."; return }
        if (!newPin.matches(Regex("[0-9]{6,12}"))) { _toast.value = "Use a new PIN with 6 to 12 digits."; return }
        if (_securityWorking.value) return
        _securityWorking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!PinLockStore.verify(getApplication(), currentPin)) {
                    _toast.value = "The current PIN did not match. The saved PIN was not changed."
                } else {
                    PinLockStore.setPin(getApplication(), newPin)
                    _toast.value = "PIN changed and securely saved."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("SECURITY", "app PIN change failed", error)
                _toast.value = "The PIN could not be changed: ${error.message ?: error.javaClass.simpleName}"
            } finally { _securityWorking.value = false }
        }
    }

    fun setAppLockTimeoutSeconds(seconds: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try { app.settings.setAppLockTimeoutSeconds(seconds) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CrashLog.record("SECURITY", "app lock delay could not be saved", error); _toast.value = "The lock delay could not be saved." }
        }
    }

    fun setHideRecentsPreview(value: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try { app.settings.setHideRecentsPreview(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CrashLog.record("SECURITY", "Recents privacy setting could not be saved", error); _toast.value = "The privacy setting could not be saved." }
        }
    }

    fun verifyAppPin(pin: String, onVerified: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val verified = try { PinLockStore.verify(getApplication(), pin) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CrashLog.record("SECURITY", "app PIN verification threw an exception", error); false }
            withContext(Dispatchers.Main.immediate) { onVerified(verified) }
        }
    }

    fun disableAppLock(currentPin: String, onDisabled: (Boolean) -> Unit) {
        if (_securityWorking.value) return
        _securityWorking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var disabled = false
            try {
                if (!PinLockStore.verify(getApplication(), currentPin)) {
                    _toast.value = "The current PIN did not match; app lock remains enabled."
                } else {
                    app.settings.setAppLockEnabled(false)
                    disabled = true
                    _toast.value = "App lock disabled. Your saved PIN is kept for re-enabling it."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CrashLog.record("SECURITY", "app lock removal failed", error); _toast.value = "App lock could not be removed." }
            finally {
                _securityWorking.value = false
                withContext(Dispatchers.Main.immediate) { onDisabled(disabled) }
            }
        }
    }

    fun exportEncryptedBackup(uri: Uri, passphrase: String, onComplete: (Boolean, Long?) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = EncryptedBackup.export(app.database, passphrase, app.settings)
                val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Android could not open the selected file for writing")
                stream.use { it.write(bytes); it.flush() }
                getApplication<Application>().getSharedPreferences("backup_status", Context.MODE_PRIVATE).edit()
                    .putLong("last_portable_backup_at", System.currentTimeMillis()).putLong("last_portable_backup_bytes", bytes.size.toLong()).apply()
                _toast.value = "Encrypted backup saved. Keep your passphrase; it is not stored by the app."
                CrashLog.record("BACKUP", "portable encrypted backup exported")
                withContext(Dispatchers.Main.immediate) { onComplete(true, bytes.size.toLong()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                CrashLog.record("BACKUP", "portable encrypted backup export failed (${error.javaClass.simpleName})", error)
                _toast.value = error.message ?: "Backup export failed."
                withContext(Dispatchers.Main.immediate) { onComplete(false, null) }
            }
        }
    }

    fun previewEncryptedBackup(uri: Uri, passphrase: String, onResult: (EncryptedBackupPreview?, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Android could not open the selected backup file.")
                val bytes = input.use { it.readBounded(32 * 1024 * 1024) }
                val preview = EncryptedBackup.preview(passphrase, bytes)
                CrashLog.record("BACKUP", "encrypted backup preview verified without restoring records")
                withContext(Dispatchers.Main.immediate) { onResult(preview, null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                CrashLog.record("BACKUP", "encrypted backup preview failed", error)
                val message = if (error.message.orEmpty().contains("decrypt", true))
                    "The backup could not be unlocked. Check the passphrase or file. Nothing has changed."
                else "This backup could not be checked: ${error.message ?: error.javaClass.simpleName}. Nothing has changed."
                withContext(Dispatchers.Main.immediate) { onResult(null, message) }
            }
        }
    }

    fun restoreEncryptedBackup(uri: Uri, passphrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Android could not open the selected backup")
                val bytes = input.use { it.readBounded(32 * 1024 * 1024) }
                val oldAlarms = app.database.dao().allAlarmsForBackup()
                EncryptedBackup.restore(app.database, passphrase, bytes, app.settings)
                oldAlarms.forEach { AlarmScheduler.cancel(getApplication(), it.id) }
                val alarms = app.database.dao().getEnabledAlarms()
                alarms.forEach { alarm -> if (!AlarmScheduler.schedule(getApplication(), alarm)) CrashLog.record("BACKUP", "restored alarm schedule was rejected id=${alarm.id}") }
                _toast.value = "Backup restored. Existing local records were replaced with the backup contents."
                CrashLog.record("BACKUP", "portable encrypted backup restored")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                CrashLog.record("BACKUP", "portable backup restore failed (${error.javaClass.simpleName})", error)
                if (EncryptedBackup.hasPendingRestoreJournal(getApplication())) app.markRestoreRecoveryPending(error)
                _toast.value = "Restore did not finish: ${error.message ?: error.javaClass.simpleName}. Check the backup and try again."
            }
        }
    }
    fun wipeAllUserData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = app.database.dao()
                app.database.withTransaction {
                    dao.allAlarmsForBackup().forEach { AlarmScheduler.cancel(getApplication(), it.id) }
                    dao.clearAllMessages(); dao.clearAllAlarms(); dao.clearAllNotes(); dao.clearAllNotifications()
                    dao.clearAllAutomationRules(); dao.clearAllMacros(); dao.clearAllClipboardHistory(); dao.clearAllWorkerCache()
                }
                app.settings.clearAllSettings()
                FeatureWorkScheduler.configure(getApplication(), false, false)
                File(getApplication<Application>().filesDir, "automatic-backups").deleteRecursively()
                _toast.value = "Local user data was cleared. The encrypted database key and built-in FAQ data remain so the app can open safely."
                CrashLog.record("PRIVACY", "user data wiped by user")
            } catch (error: Exception) { CrashLog.record("PRIVACY", "user data wipe failed", error); _toast.value = "Data wipe did not finish: ${error.message ?: "database error"}" }
        }
    }
    fun clearToast() { _toast.value = null }
    fun setToast(value: String) { _toast.value = value }

    private fun stepFor(text: String): String = when {
        text.contains("notification", true) || text.contains("whatsapp", true) -> "Searching saved notifications on this device"
        text.contains("weather", true) || text.contains("forecast", true) -> "Looking up the requested location with Open-Meteo"
        text.contains("news", true) || text.contains("headlines", true) -> "Reading the saved RSS feed"
        text.contains("alarm", true) || text.contains("remind", true) -> "Checking time and scheduling an Android alarm clock"
        text.contains("pdf", true) || text.startsWith("http", true) -> "Extracting source text and ranking key sentences"
        else -> "Matching your request to local rules"
    }

    private fun nextAlarmTime(alarm: AlarmEntity): Long {
        val now = System.currentTimeMillis()
        if (alarm.cron != null) {
            val next = runCatching { CronSchedule.parse(alarm.cron).nextAfter(LocalDateTime.now()) }.getOrNull()
            if (next != null) return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        if (alarm.triggerAt > now) return alarm.triggerAt
        val source = Calendar.getInstance().apply { timeInMillis = alarm.triggerAt }
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, source.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    override fun onCleared() {
        voiceStop?.complete(Unit)
        diagnosticJob?.cancel()
        try { androidSpeechController.release() }
        catch (error: Exception) { CrashLog.record("VOICE", "shared Android speech controller cleanup failed", error) }
        super.onCleared()
    }

    companion object { private const val QUICK_ALARM_REQUEST = 94003 }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "Backup file is larger than 32 MB." }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
