package com.cue.offlinebrain.diagnostics

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationManagerCompat
import com.cue.offlinebrain.MainActivity
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.R
import com.cue.offlinebrain.accessibility.DeviceActions
import com.cue.offlinebrain.accessibility.MacroCaptureSession
import com.cue.offlinebrain.alarm.AlarmDismissalPolicy
import com.cue.offlinebrain.data.AlarmEntity
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.EncryptedBackup
import com.cue.offlinebrain.data.LearnedPhrase
import com.cue.offlinebrain.data.LearnedPhraseMatcher
import com.cue.offlinebrain.data.MacroEntity
import com.cue.offlinebrain.data.MacroStepParser
import com.cue.offlinebrain.data.MacroStepEditorModel
import com.cue.offlinebrain.data.TypedMacroStep
import com.cue.offlinebrain.data.TypedMacroSteps
import com.cue.offlinebrain.data.RestoreTransaction
import com.cue.offlinebrain.data.PhraseChainParser
import com.cue.offlinebrain.data.NotificationEntity
import com.cue.offlinebrain.data.PinLockStore
import com.cue.offlinebrain.data.AppLockStateStore
import com.cue.offlinebrain.data.PortableBackupCrypto
import com.cue.offlinebrain.data.SettingsStore
import com.cue.offlinebrain.device.DeviceStatusProvider
import com.cue.offlinebrain.device.VolumeCommand
import com.cue.offlinebrain.device.VolumeCommandParser
import com.cue.offlinebrain.device.VolumeLevelPolicy
import com.cue.offlinebrain.engine.IntentEngine
import com.cue.offlinebrain.engine.CompoundCommandParser
import com.cue.offlinebrain.engine.SmallTalkRouter
import com.cue.offlinebrain.engine.InstalledCommandApp
import com.cue.offlinebrain.notifications.BrainNotificationListener
import com.cue.offlinebrain.notifications.NotificationAccessState
import com.cue.offlinebrain.notifications.NotificationPrivacyFormatter
import com.cue.offlinebrain.notifications.NotificationQueryParser
import com.cue.offlinebrain.notifications.NotificationQueryRegressionCases
import com.cue.offlinebrain.notifications.NotificationSavePath
import com.cue.offlinebrain.overlay.OverlayPosition
import com.cue.offlinebrain.overlay.OverlayPositionPolicy
import com.cue.offlinebrain.voice.SpeechDiagnostics
import com.cue.offlinebrain.voice.SpeechInputStateMachine
import com.cue.offlinebrain.voice.SpeechPhase
import com.cue.offlinebrain.worker.FeatureWorkScheduler
import com.cue.offlinebrain.summary.DocumentKind
import com.cue.offlinebrain.summary.ResumeDocumentParser
import com.cue.offlinebrain.summary.ResumeEvidenceMarkup
import androidx.work.WorkManager
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class DiagnosticResult(val name: String, val status: Status, val reason: String) {
    enum class Status { RUNNING, PASS, FAIL, UNTESTED }
}

class DiagnosticsRunner(context: Context) {
    private val appContext = context.applicationContext
    private val ocrLock = Any()
    @Volatile private var cachedOcrRecognizer: TextRecognizer? = null

    suspend fun runAll(onResult: (DiagnosticResult) -> Unit) {
        val checks: List<Pair<String, suspend () -> String>> = listOf(
            "Chat input presence and focus" to { "UNTESTED — the instrumented Compose test checks the chat input tag and click action; this app-level runner cannot inspect the currently visible Compose tree." },
            "Encrypted database and notification search" to { notificationDatabase() },
            "Notification pipeline status" to { notificationPipelineStatus() },
            "Notification test capture and real processor" to { notificationPipelineRoundTrip() },
            "Notification query phrase suite" to { notificationQueryPhraseSuite() },
            "Notification card privacy rules" to { notificationCardPrivacyDryRun() },
            "Compound command parser dry run" to { commandParserDryRun() },
            "Small-talk routing regression" to { smallTalkRegressionDryRun() },
            "PDF cleanup sample" to { pdfCleanupSample() },
            "Resume Q&A and highlights fixture" to { resumeQADryRun() },
            "Phrase add/match/delete round trip" to { phraseRoundTrip() },
            "Phrase chain dry run" to { phraseChainDryRun() },
            "Macro capture and routing dry run" to { macroRoundTrip() },
            "Macro manual-step dry run" to { macroManualStepDryRun() },
            "Room and settings restore rollback dry run" to { restoreRollbackDryRun() },
            "Clipboard history setting" to { clipboardSetting() },
            "App lock state and safe PIN verification" to { appLockSetting() },
            "Encrypted backup and wrong-passphrase check" to { backupCryptoCheck() },
            "Backup preview wizard dry run" to { backupPreviewDryRun() },
            "Floating button permission and start/stop" to { overlayCheck() },
            "Floating bubble position dry run" to { overlayPositionDryRun() },
            "WorkManager states and last run" to { workerStates() },
            "FileProvider share URI" to { fileProviderUri() },
            "One-time alarm cleanup policy" to { alarmCleanupPolicy() },
            "Notification channel" to { notificationChannel() },
            "TTS ready" to { ttsReady() },
            "Accessibility service" to { accessibilityBound() },
            "Notification listener" to { notificationListenerBound() },
            "Screenshot capability" to { screenshotCapability() },
            "OCR sample image" to { ocrSample() },
            "Network" to { network() },
            "Device status summary" to { deviceStatusDryRun() },
            "Media volume control dry run" to { volumeCommandDryRun() },
            "Default speech recognizer" to { speechRecognizer() },
            "Microphone state-machine dry run" to { speechStateMachineDryRun() },
            "Advanced Vosk model (optional)" to { voskModel() }
        )
        for ((name, check) in checks) {
            onResult(DiagnosticResult(name, DiagnosticResult.Status.RUNNING, "Checking…"))
            val reason = try { check() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("DIAGNOSTIC", "$name threw an exception", error)
                "FAIL — ${error.message ?: error.javaClass.simpleName}"
            }
            val status = when {
                reason.startsWith("PASS") -> DiagnosticResult.Status.PASS
                reason.startsWith("UNTESTED") -> DiagnosticResult.Status.UNTESTED
                else -> DiagnosticResult.Status.FAIL
            }
            val result = DiagnosticResult(name, status, reason.removePrefix("PASS — ").removePrefix("FAIL — ").removePrefix("UNTESTED — "))
            CrashLog.record("DIAGNOSTIC", "$name ${result.status}: ${result.reason}")
            onResult(result)
        }
        onResult(DiagnosticResult("Alarm schedule + fire (1 minute)", DiagnosticResult.Status.RUNNING, "Scheduling a user-visible test alarm for about one minute from now…"))
        val alarmResult = try { alarmScheduleAndFire() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CrashLog.record("DIAGNOSTIC", "alarm schedule/fire test failed", error)
            "FAIL — ${error.message ?: error.javaClass.simpleName}"
        }
        val passed = alarmResult.startsWith("PASS")
        onResult(DiagnosticResult("Alarm schedule + fire (1 minute)", if (passed) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL, alarmResult.removePrefix("PASS — ").removePrefix("FAIL — ")))
    }

    private suspend fun notificationDatabase(): String = withContext(Dispatchers.IO) {
        val app = appContext as? OfflineBrainApplication ?: return@withContext "FAIL — app database is unavailable."
        val dao = app.database.dao()
        val total = dao.notificationRowCount()
        val latest = dao.latestNotificationTime()
        val categories = dao.notificationCategoryCounts().joinToString { "${it.category}:${it.count}" }.ifBlank { "none" }
        val sample = dao.recentNotifications().firstOrNull()
        val search = dao.searchNotifications(sample?.packageName?.take(100).orEmpty())
        val saved = latest?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "none"
        "PASS — database opened; $total notification row(s), latest $saved; categories $categories; package search returned ${search.size} row(s). No message bodies included."
    }

    private suspend fun phraseRoundTrip(): String = withContext(Dispatchers.IO) {
        val app = appContext as? OfflineBrainApplication ?: return@withContext "FAIL — settings are unavailable."
        val marker = "cue-diagnostic-${System.currentTimeMillis()}"
        var added = false
        try {
            app.settings.addLearnedPhrase(marker, "weather")
            added = true
            val saved = app.settings.settings.first().learnedPhrases.firstOrNull { it.phrase == marker }
            if (saved == null || LearnedPhraseMatcher.firstMatch("please $marker", listOf(saved)) != saved)
                "FAIL — the temporary phrase could not be saved or matched."
            else "PASS — temporary phrase saved, fixed-word matched and removed; no user phrase was changed."
        } finally { if (added) runCatching { app.settings.deleteLearnedPhrase(marker) } }
    }

    private suspend fun notificationPipelineStatus(): String = withContext(Dispatchers.IO) {
        val app = appContext as? OfflineBrainApplication ?: return@withContext "FAIL — app settings are unavailable."
        val state = com.cue.offlinebrain.notifications.NotificationPipelineTelemetry.snapshot.value
        val settings = app.settings.settings.first()
        val skipped = com.cue.offlinebrain.notifications.NotificationDecisionReason.values()
            .filter { it != com.cue.offlinebrain.notifications.NotificationDecisionReason.SAVED }
            .joinToString { "${it.code}:${state.skippedByReason[it.code] ?: 0}" }
        val recent = state.recent.joinToString(" | ") { "${it.packageName}:${it.reason}" }.ifBlank { "none" }
        val savedAt = com.cue.offlinebrain.notifications.NotificationAccessState.lastSavedAt(app)
            .takeIf { it > 0L }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "none"
        val callbackAt = state.lastCallbackAt.takeIf { it > 0L }
            ?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "none since this process started"
        val removedAt = state.lastRemovedAt.takeIf { it > 0L }
            ?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "none since this process started"
        val connectedAt = com.cue.offlinebrain.notifications.NotificationAccessState.lastConnectedAt(app)
            .takeIf { it > 0L }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "not recorded"
        val disconnectedAt = com.cue.offlinebrain.notifications.NotificationAccessState.lastDisconnectedAt(app)
            .takeIf { it > 0L }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "not recorded"
        "PASS — since app start: received=${state.received}, saved=${state.saved}, removed=${state.removed}, skipped=[$skipped]; live capture=${if (settings.notificationLiveCaptureEnabled) "on" else "off"}; listener connected=${com.cue.offlinebrain.notifications.NotificationAccessState.connected}, last callback=$callbackAt, last removal=$removedAt, last connect=$connectedAt, last disconnect=$disconnectedAt; last saved=$savedAt; recent package/reason only=[$recent]."
    }

    private suspend fun notificationPipelineRoundTrip(): String = withContext(Dispatchers.IO) {
        val app = appContext as? OfflineBrainApplication ?: return@withContext "FAIL — app database is unavailable."
        val settings = app.settings.settings.first()
        if (!settings.notificationLiveCaptureEnabled) return@withContext "UNTESTED — live capture is off; turn it on in Notifications before testing the filtered save path."
        val liveCapture = com.cue.offlinebrain.notifications.NotificationTestCapture.postAndWait(app)
        val liveDecision = liveCapture.decision
            ?: return@withContext "FAIL — the app's own test notification did not receive a real listener decision: ${liveCapture.error ?: "no decision"}. Check access, connection and callback counters."
        if (!liveDecision.saved) {
            val policySkip = liveDecision.reason in setOf(
                com.cue.offlinebrain.notifications.NotificationDecisionReason.BLOCKED_APP,
                com.cue.offlinebrain.notifications.NotificationDecisionReason.NOT_IN_ALLOW_LIST
            )
            return@withContext if (policySkip)
                "UNTESTED — the real app-owned callback reached the processor but the current app filters returned ${liveDecision.reason.code}; no record was forced through."
            else "FAIL — the real app-owned callback reached the processor but was ${liveDecision.summary}."
        }
        if (liveCapture.error != null) return@withContext "FAIL — the real listener saved the test item but cleanup reported: ${liveCapture.error}"
        val dao = app.database.dao()
        val tag = "cue-pipeline-diagnostic-${System.currentTimeMillis()}"
        val sbn = com.cue.offlinebrain.notifications.NotificationTestCapture.synthetic(app, "com.cue.diagnostic", tag)
        val key = sbn.key.orEmpty()
        try {
            val before = dao.notificationRowCount()
            val first = com.cue.offlinebrain.notifications.NotificationCapturePipeline.processNotification(app, sbn, "diagnostics")
            if (!first.saved) {
                val policySkip = first.reason in setOf(
                    com.cue.offlinebrain.notifications.NotificationDecisionReason.BLOCKED_APP,
                    com.cue.offlinebrain.notifications.NotificationDecisionReason.NOT_IN_ALLOW_LIST
                )
                return@withContext if (policySkip)
                    "UNTESTED — the real processor correctly applied your current app filters to com.cue.diagnostic (${first.reason.code}); no record was forced through."
                else "FAIL — the shared notification processor returned ${first.summary}; inspect pipeline status for the recorded package/category/flags/reason."
            }
            val afterInsert = dao.notificationRowCount()
            kotlinx.coroutines.delay(1_100L)
            val duplicate = com.cue.offlinebrain.notifications.NotificationCapturePipeline.processNotification(app, sbn, "diagnostics-replay")
            val afterDuplicate = dao.notificationRowCount()
            if (afterInsert != before + 1 || duplicate.reason != com.cue.offlinebrain.notifications.NotificationDecisionReason.DUPLICATE || afterDuplicate != afterInsert)
                "FAIL — the real processor did not insert once and reject the later persistent duplicate."
            else "PASS — the app-owned test notification arrived through onNotificationPosted and was saved/cleaned; a synthetic SBN then used the same callback processor, filter/extractor/Room path, inserted once and rejected a stale replay."
        } finally { if (key.isNotBlank()) dao.deleteNotificationByDedupeKey(key) }
    }

    private fun commandParserDryRun(): String {
        val parsed = CompoundCommandParser.parse(
            "open Chrome and search books",
            listOf(InstalledCommandApp("Chrome", "com.android.chrome"))
        )
        val plan = parsed.plan
        if (!parsed.recognized || parsed.error != null || plan?.actions?.size != 2)
            return "FAIL — parser did not create the two expected safe steps."
        if (plan.actions.firstOrNull()?.kind != com.cue.offlinebrain.engine.CommandActionKind.OPEN_APP ||
            plan.actions.lastOrNull()?.kind != com.cue.offlinebrain.engine.CommandActionKind.WEB_SEARCH)
            return "FAIL — parser action order or search route was incorrect."
        return "PASS — dry-run produced an ordered app-open and direct web-search plan; no app was launched, no text was typed, and no network request was sent."
    }

    private fun smallTalkRegressionDryRun(): String {
        val phrases = listOf(
            "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
            "how are you", "how are you doing", "how is it going", "thanks", "thank you",
            "thanks a lot", "thank you very much", "who are you", "what are you",
            "what is your name", "who made you", "what can you do", "what do you do",
            "how can you help me", "can you help me", "tell me a joke", "tell me a funny joke",
            "joke", "funny joke", "what time is it", "what is the time", "time",
            "time now", "time right now", "what is today's date", "what is the date",
            "date", "date today", "what day is it", "what day is it today",
            "what is the day", "which day is it", "hi there", "hello friend"
        )
        if (phrases.size != 40 || phrases.toSet().size != 40) return "FAIL — the small-talk regression list is not 40 unique phrases."
        val router = SmallTalkRouter()
        val failures = phrases.filter { phrase -> router.reply(phrase).let { it.isNullOrBlank() || it.contains("accessibility", ignoreCase = true) } }
        val actionsRemainUnclaimed = listOf("open Accessibility settings", "turn on accessibility service", "tap Search").all { router.reply(it) == null }
        return if (failures.isEmpty() && actionsRemainUnclaimed)
            "PASS — all 40 distinct non-action phrases received non-Accessibility replies; explicit screen-action requests remain available to the action router."
        else "FAIL — ${failures.size} small-talk phrase(s) or explicit action controls were misrouted."
    }

    private fun resumeQADryRun(): String {
        val sample = """Alex Morgan
            alex.morgan@example.com | +1 415 555 0182
            SUMMARY
            Software engineer with experience building reliable payment platforms.
            SKILLS
            Kotlin, Java, SQL, Android
            EXPERIENCE
            Senior Engineer | Tech Mahindra | Pune | 2021 - Present
            - Built payment services using Kotlin and SQL.
            Engineer | Acme Systems | Mumbai | 2018 - 2021 | Consultant | Globex | Remote | 2016 - 2018
            - Designed Java APIs for customer onboarding.
            EDUCATION
            BSc Computer Science, Example University, 2018
            LANGUAGES
            English, Hindi""".trimIndent()
        val parser = ResumeDocumentParser()
        val parsed = parser.parse(sample)
        val focused = parser.answer(sample, "What experience in Tech Mahindra?")
        val missing = parser.answer(sample, "Does Alex know Rust?")
        val highlightVisible = ResumeEvidenceMarkup.segments(focused.text).any { it.highlighted }
        val okay = parsed.kind == DocumentKind.RESUME && parsed.jobs.size == 3 && focused.found &&
            focused.text.contains("Tech Mahindra") && !focused.text.contains("Acme Systems") &&
            !focused.text.contains("Globex") && highlightVisible && !missing.found
        return if (okay) "PASS — local resume fixture split three pipe-delimited jobs, answered only the requested employer block, marked evidence for UI highlighting and abstained on missing skills; no user PDF was opened."
        else "FAIL — resume parser, focused answer, highlighting or explicit not-found check failed."
    }

    private fun pdfCleanupSample(): String {
        val raw = """REPORT HEADER
            Page 1 of 2
            1. Findings
            The study shows that structured cleanup improves the retriev-
            able text produced by a PDF parser. Researchers checked each sentence against the original page. A clear title helps readers understand the document. The summary keeps the source sentence order. Teams can review the cleaned result before sharing it.
            1
            \u000cREPORT HEADER
            Page 2 of 2
            Reviewers should check dates and names before relying on extracted text.
            REPORT HEADER""".trimIndent().replace("\\u000c", "\u000c")
        val report = com.cue.offlinebrain.summary.PdfTextCleanup.report(raw, "Diagnostic PDF")
        return if (report.cleanedText.contains("retrievable text") && !report.cleanedText.contains("Page 1") &&
            report.summary.size in 3..5 && report.toChatText().contains("Sections:"))
            "PASS — cleanup removed page labels, joined a split word, and built an extractive summary, key-point and sections report from a bundled text sample; no user PDF was opened."
            else "FAIL — PDF cleanup sample did not produce the expected clean text and report."
    }

    private fun speechStateMachineDryRun(): String {
        val machine = SpeechInputStateMachine()
        machine.start()
        machine.partial("diagnostic sample")
        machine.stopRequested()
        val result = machine.result("")
        val okay = result.phase == SpeechPhase.RESULT && result.finalText == "diagnostic sample" && result.secondsRemaining == 0
        return if (okay) "PASS — idle → listening → processing → result and partial-text fallback passed; last Android mic state: ${SpeechDiagnostics.lastStatus.take(160)}. No audio was recorded."
        else "FAIL — microphone state-machine dry run failed; last Android mic state: ${SpeechDiagnostics.lastStatus.take(160)}."
    }

    private fun backupPreviewDryRun(): String {
        val sample = org.json.JSONObject().put("version", 1)
            .put("messages", org.json.JSONArray().put(org.json.JSONObject()))
            .put("alarms", org.json.JSONArray().put(org.json.JSONObject()))
            .put("notes", org.json.JSONArray())
            .put("notifications", org.json.JSONArray())
            .put("rules", org.json.JSONArray())
            .put("macros", org.json.JSONArray().put(org.json.JSONObject()).put(org.json.JSONObject()))
            .put("clipboard", org.json.JSONArray())
            .put("preferences", org.json.JSONObject().put("learnedPhrases", org.json.JSONArray().put(org.json.JSONObject())).put("accent", "teal"))
        val passphrase = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
        val encrypted = PortableBackupCrypto.encrypt(passphrase, sample.toString().toByteArray(Charsets.UTF_8))
        val preview = EncryptedBackup.preview(passphrase, encrypted)
        val wrongPassphraseRejected = try {
            EncryptedBackup.preview("incorrect diagnostics passphrase", encrypted)
            false
        } catch (error: Exception) {
            CrashLog.record("DIAGNOSTIC", "expected wrong-passphrase rejection in backup preview dry run", error)
            true
        }
        return if (preview.messages == 1 && preview.alarms == 1 && preview.macros == 2 && preview.phrases == 1 && preview.settings == 2 && wrongPassphraseRejected)
            "PASS — encrypted preview returned the expected record counts; a wrong passphrase was rejected before any database or settings operation. Synthetic data only."
        else "FAIL — backup preview dry run returned unexpected counts or accepted a wrong passphrase."
    }

    private suspend fun macroRoundTrip(): String = withContext(Dispatchers.IO) {
        if (MacroCaptureSession.isActive) return@withContext "UNTESTED — a user macro recording is active; diagnostics did not stop or change it."
        val app = appContext as? OfflineBrainApplication ?: return@withContext "FAIL — app database is unavailable."
        val dao = app.database.dao()
        val name = "Cue diagnostic ${System.currentTimeMillis()}"
        var id = 0L
        var captureActive = false
        try {
            MacroCaptureSession.start()
            captureActive = true
            listOf("Open WhatsApp", "Tap Search", "Type Mom").forEach(MacroCaptureSession::append)
            val captured = MacroCaptureSession.stopAndTake()
            captureActive = false
            val routed = captured.map { IntentEngine().classify(it).name }
            if (captured != listOf("Open WhatsApp", "Tap Search", "Type Mom") || routed != listOf("open_app", "tap", "tap"))
                return@withContext "FAIL — temporary capture or local command routing dry run failed."
            id = dao.saveMacro(MacroEntity(name = name, steps = captured.joinToString("\n")))
            val saved = dao.macroByName(name)
            val parsed = saved?.let { com.cue.offlinebrain.data.MacroStepParser.parse(it.steps) }.orEmpty()
            if (parsed != captured) "FAIL — temporary macro save/parse check failed."
            else "PASS — three temporary steps captured, routed and saved/parsed/deleted; no real app was opened or tapped. Android screen-event capture still needs a phone test."
        } finally {
            if (captureActive) runCatching { MacroCaptureSession.stopAndTake() }
            if (id > 0) runCatching { dao.deleteMacro(id) }
            runCatching { dao.deleteMacrosByName(name) }
        }
    }

    private fun phraseChainDryRun(): String {
        val steps = PhraseChainParser.parse("device status\nset an alarm for 6 am\nopen Chrome and search cats")
        val deviceQuery = steps.firstOrNull()?.let { DeviceStatusProvider.classify(it) == DeviceStatusProvider.Request.SUMMARY } == true
        val alarmQuery = steps.getOrNull(1)?.let { IntentEngine().classify(it).name == "alarm" } == true
        val searchPlan = steps.getOrNull(2)?.let { CompoundCommandParser.parse(it, listOf(InstalledCommandApp("Chrome", "com.android.chrome"))) }
        return if (steps.size == 3 && deviceQuery && alarmQuery && searchPlan?.plan?.actions?.size == 2)
            "PASS — a three-action phrase chain was parsed and routed in order to Device Status, alarm and browser-search plans; none was executed."
        else "FAIL — phrase chain dry-run parsing or local action routing did not match the sample."
    }

    private fun macroManualStepDryRun(): String {
        val drafts = listOf(
            MacroStepEditorModel.newDraft(TypedMacroStep.OpenApp("Chrome")),
            MacroStepEditorModel.newDraft(TypedMacroStep.Wait(2)),
            MacroStepEditorModel.newDraft(TypedMacroStep.RunChatCommand("search cats"))
        )
        val roundTrip = runCatching { MacroStepEditorModel.drafts(MacroStepEditorModel.encode(drafts)) }.getOrNull()
            ?: return "FAIL — typed macro codec round-trip failed."
        val inserted = MacroStepEditorModel.insertAfter(roundTrip, 0, MacroStepEditorModel.newDraft(TypedMacroStep.Scroll))
        val descriptions = inserted.map { MacroStepEditorModel.describe(it.step) }
        val valid = MacroStepEditorModel.validate(roundTrip, emptyList(), null) == null
        return if (roundTrip.map { it.id } == drafts.map { it.id } && inserted.size == 4 && valid &&
            descriptions.firstOrNull() == "Open Chrome" && descriptions.getOrNull(1) == "Scroll down" &&
            TypedMacroSteps.decode(TypedMacroSteps.encode(roundTrip.map { it.step })) == roundTrip.map { it.step })
            "PASS — three typed steps kept stable IDs and order; codec, validation and insert-after passed. No wait, app launch, speech or search was executed."
        else "FAIL — typed macro model, insertion or codec dry run failed."
    }

    private suspend fun restoreRollbackDryRun(): String = withContext(Dispatchers.IO) {
        val oldDatabase = listOf("old row")
        val oldSettings = mapOf("city" to "Delhi", "lock" to "on")
        var database = oldDatabase
        var settings = oldSettings
        var injectedFailureSeen = false
        try {
            RestoreTransaction.apply(
                replaceDatabase = { database = listOf("new row") },
                replaceSettings = { settings = mapOf("city" to "Mumbai", "lock" to "off"); throw IllegalStateException("diagnostic injection") },
                rollbackBoth = { database = oldDatabase; settings = oldSettings }
            )
        } catch (error: IllegalStateException) { injectedFailureSeen = error.message == "diagnostic injection" }
        if (injectedFailureSeen && database == oldDatabase && settings == oldSettings)
            "PASS — injected failure after both in-memory replacements restored both original snapshots; no user database or settings were changed."
        else "FAIL — the cross-store rollback dry run did not return both stores to their original values."
    }

    private suspend fun clipboardSetting(): String {
        val app = appContext as? OfflineBrainApplication ?: return "FAIL — settings are unavailable."
        val enabled = app.settings.settings.first().clipboardHistoryEnabled
        return "PASS — clipboard history is ${if (enabled) "on" else "off"}; diagnostic did not read clipboard contents or change this setting."
    }

    private suspend fun appLockSetting(): String {
        val app = appContext as? OfflineBrainApplication ?: return "FAIL — settings are unavailable."
        val enabled = app.settings.settings.first().appLockEnabled
        val pinStored = PinLockStore.hasPin(appContext)
        val mirrorEnabled = AppLockStateStore.isEnabled(appContext)
        if (!AppLockStateStore.isInitialized(appContext)) return "FAIL — synchronous lock state was not initialized."
        if (enabled != mirrorEnabled) return "FAIL — DataStore and the synchronous lock-state mirror disagree; review App lock settings."
        if (enabled && !pinStored) return "FAIL — app lock is enabled, but Android could not read its saved encrypted PIN verifier."
        val syntheticHash = runCatching { com.cue.offlinebrain.data.PinHasher.create("847291") }.getOrNull()
            ?: return "FAIL — in-memory PIN hashing could not run."
        val verifierWorks = com.cue.offlinebrain.data.PinHasher.verify("847291", syntheticHash) &&
            !com.cue.offlinebrain.data.PinHasher.verify("111111", syntheticHash)
        if (!verifierWorks) return "FAIL — in-memory PIN verification did not accept/reject the expected test values."
        val delaySeconds = app.settings.settings.first().appLockTimeoutSeconds
        val lastBackground = com.cue.offlinebrain.data.AppLockLifecycle.runtimeStatus.value.lastBackgroundAtElapsedMillis
        val backgroundAge = lastBackground?.let { at ->
            val age = (android.os.SystemClock.elapsedRealtime() - at).coerceAtLeast(0L) / 1_000L
            when {
                age < 60L -> "${age}s ago"
                age < 3_600L -> "${age / 60L}m ago"
                else -> "${age / 3_600L}h ago"
            }
        } ?: "not recorded since this process started"
        val delayLabel = when (delaySeconds) {
            0 -> "Immediately"; 30 -> "30 seconds"; 60 -> "1 minute"; 120 -> "2 minutes"; 300 -> "5 minutes"; 900 -> "15 minutes"; else -> "2 minutes (safe default)"
        }
        return "PASS — app lock is ${if (enabled) "on" else "off"}; selected relock delay: $delayLabel; last true background transition: $backgroundAge; PIN verifier is ${if (pinStored) "readable from encrypted storage" else "not set"}; lock state is stored separately; a synthetic PIN passed hash/verify in memory. No saved PIN, lockout counter, or biometric prompt was touched."
    }

    private fun backupCryptoCheck(): String = try {
        val original = "diagnostic-only sample".toByteArray(Charsets.UTF_8)
        val encrypted = com.cue.offlinebrain.data.PortableBackupCrypto.encrypt("cue-diagnostic-passphrase", original)
        val restored = com.cue.offlinebrain.data.PortableBackupCrypto.decrypt("cue-diagnostic-passphrase", encrypted)
        val wrongPassRejected = runCatching { com.cue.offlinebrain.data.PortableBackupCrypto.decrypt("wrong-diagnostic-passphrase", encrypted) }.isFailure
        if (original.contentEquals(restored) && wrongPassRejected) "PASS — in-memory encryption round trip succeeded and a wrong passphrase was rejected; no user backup was opened or restored."
        else "FAIL — in-memory backup integrity or wrong-passphrase check failed."
    } catch (error: Exception) { "FAIL — backup crypto test failed: ${error.message ?: error.javaClass.simpleName}." }

    private fun overlayCheck(): String {
        val granted = runCatching { Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        val overlay = com.cue.offlinebrain.overlay.OverlayDiagnostics.snapshot()
        return when {
            overlay.viewAttached -> "PASS — the floating ComposeView is attached; overlay permission is ${if (granted) "granted" else "not granted"}."
            !overlay.lastError.isNullOrBlank() -> "FAIL — the last floating-view attach attempt failed with ${overlay.lastError}; overlay permission is ${if (granted) "granted" else "not granted"}. Use Tools → Floating button → Show bubble for 10 seconds to retry."
            else -> "UNTESTED — overlay permission is ${if (granted) "granted" else "not granted"}; no attach error has been recorded. Use Tools → Floating button → Show bubble for 10 seconds to test it explicitly."
        }
    }

    private suspend fun workerStates(): String = withContext(Dispatchers.IO) {
        val names = listOf(FeatureWorkScheduler.REFRESH_NAME, FeatureWorkScheduler.BACKUP_NAME, FeatureWorkScheduler.LISTENER_HEALTH_NAME, "local-privacy-retention-cleanup")
        val prefs = appContext.getSharedPreferences("feature_work_status", Context.MODE_PRIVATE)
        val manager = WorkManager.getInstance(appContext)
        val lines = names.map { name ->
            val state = runCatching { manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).firstOrNull()?.state?.name ?: "not scheduled" }.getOrElse { "unavailable" }
            val at = prefs.getLong("last_run_$name", 0L)
            val last = if (at > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at)) else "no recorded run yet"
            val outcome = prefs.getString("last_result_$name", "not recorded")
            "$name=$state; last run $last ($outcome)"
        }
        "PASS — ${lines.joinToString(" · ")}. Android may defer scheduled work for battery/network limits."
    }

    private fun fileProviderUri(): String = try {
        val share = CrashLog.shareIntent(appContext)
        val uri = share.extras?.get(Intent.EXTRA_STREAM) as? Uri
        if (share.type != "text/plain" || uri == null) return "FAIL — file-sharing URI was not created."
        if (share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return "FAIL — read permission was not granted for the shared log."
        if (share.clipData?.itemCount != 1 || share.clipData?.getItemAt(0)?.uri != uri) return "FAIL — share ClipData does not point to the log URI."
        val content = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return "FAIL — the app could not open the shared log content URI."
        if (content.isBlank()) "FAIL — the shared diagnostics log stream was empty."
        else "PASS — generated the real share intent, verified read-grant and ClipData, and opened the dedicated diagnostics log URI; no share sheet was opened."
    } catch (error: Exception) { "FAIL — file-sharing URI failed: ${error.message ?: error.javaClass.simpleName}." }

    private fun alarmCleanupPolicy(): String {
        val oneTime = AlarmEntity(label = "diagnostic", triggerAt = 1L)
        val recurring = AlarmEntity(label = "diagnostic recurring", triggerAt = 1L, cron = "0 8 * * 1")
        return if (AlarmDismissalPolicy.removeAfterStop(oneTime) && AlarmDismissalPolicy.replaceAfterSnooze(oneTime) &&
            !AlarmDismissalPolicy.removeAfterStop(recurring) && !AlarmDismissalPolicy.replaceAfterSnooze(recurring))
            "PASS — the shared rule removes/replaces one-time alarms after Stop/Snooze and keeps repeating alarms; no live alarm was changed."
        else "FAIL — one-time alarm cleanup policy check failed."
    }

    private fun notificationChannel(): String = try {
        if (Build.VERSION.SDK_INT < 26) return "PASS — notification channels are not required before Android 8."
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "FAIL — NotificationManager is unavailable."
        val channelId = com.cue.offlinebrain.alarm.AlarmAnnouncementService.channelId(appContext)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val channel = NotificationChannel(channelId, "Alarms and reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Exact-time alarms and reminders"
            setSound(com.cue.offlinebrain.alarm.AlarmPreferences.selectedSoundUri(appContext), attrs)
        }
        manager.createNotificationChannel(channel)
        val saved = manager.getNotificationChannel(channelId)
        when {
            saved == null -> "FAIL — Android did not create the alarm channel."
            saved.importance < NotificationManager.IMPORTANCE_HIGH -> "FAIL — alarm channel importance is below HIGH; check its notification settings."
            saved.sound == null -> "FAIL — alarm channel has no sound; select an alarm sound in Android notification settings."
            else -> "PASS — high-importance alarm channel exists with an alarm sound."
        }
    } catch (error: Exception) { "FAIL — ${error.message ?: "channel check failed"}" }

    private suspend fun ttsReady(): String = withTimeout(12_000L) {
        suspendCancellableCoroutine { continuation ->
            var engine: TextToSpeech? = null
            try {
                engine = TextToSpeech(appContext) { status ->
                    val current = engine
                    val result = if (status != TextToSpeech.SUCCESS || current == null) {
                        "FAIL — Android Text-to-Speech did not initialize (status $status)."
                    } else {
                        val language = current.isLanguageAvailable(Locale.getDefault())
                        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
                            "FAIL — no TTS voice is available for ${Locale.getDefault().displayName}."
                        else "PASS — Android Text-to-Speech is ready for ${Locale.getDefault().displayName}."
                    }
                    if (continuation.isActive) continuation.resume(result)
                    runCatching { current?.shutdown() }
                }
                continuation.invokeOnCancellation { runCatching { engine.shutdown() } }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resume("FAIL — TTS service could not start: ${error.message ?: error.javaClass.simpleName}")
                runCatching { engine?.shutdown() }
            }
        }
    }

    private fun accessibilityBound(): String {
        if (DeviceActions.available()) return "PASS — accessibility service is bound."
        val enabled = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().contains(appContext.packageName, ignoreCase = true)
        }.getOrDefault(false)
        return if (!enabled) "FAIL — Accessibility is not enabled for this app in Android Accessibility settings."
        else "FAIL — Accessibility is enabled, but the service is not bound yet; return to the app or restart the service."
    }

    private suspend fun notificationListenerBound(): String {
        val enabled = runCatching { NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName) }.getOrDefault(false)
        val liveCapture = (appContext as? OfflineBrainApplication)?.settings?.settings?.first()?.notificationLiveCaptureEnabled ?: false
        if (!enabled) return "FAIL — Notification Access is off; live capture is ${if (liveCapture) "on" else "off"}. Enable access in Android's Notification access settings."
        if (NotificationAccessState.connected) return "PASS — notification listener is connected; live capture is ${if (liveCapture) "on" else "off"}."
        NotificationAccessState.repair(appContext)
        repeat(10) {
            delay(500)
            if (NotificationAccessState.connected) return "PASS — notification listener reconnected; live capture is ${if (liveCapture) "on" else "off"}."
        }
        return "FAIL — Notification Access is enabled but Android has not connected the listener; inspect MIUI background settings and retry."
    }

    private suspend fun screenshotCapability(): String = suspendCancellableCoroutine { continuation ->
        DeviceActions.testScreenshot { result -> if (continuation.isActive) continuation.resume(result) }
    }

    private suspend fun ocrSample(): String = withContext(Dispatchers.Default) {
        val bitmap = appContext.assets.open("diagnostics_sample.png").use { BitmapFactory.decodeStream(it) }
            ?: return@withContext "FAIL — bundled OCR sample image could not be decoded."
        val recognizer = withContext(Dispatchers.IO) {
            synchronized(ocrLock) {
                cachedOcrRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .also { cachedOcrRecognizer = it }
            }
        }
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(image)
            val text = suspendCancellableCoroutine<String> { continuation ->
                task.addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result.text) }
                task.addOnFailureListener { error ->
                    val detail = buildString {
                        append(error.javaClass.simpleName)
                        if (error is MlKitException) append(" code=${error.errorCode}")
                        error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    }
                    CrashLog.record("DIAGNOSTIC", "bundled OCR sample failed: $detail", error)
                    if (continuation.isActive) continuation.resume("__ERROR__:$detail")
                }
            }
            if (text.startsWith("__ERROR__:")) "FAIL — ${text.removePrefix("__ERROR__:")}" else if (text.contains("OFFLINE BRAIN", true) && text.contains("OCR TEST", true))
                "PASS — bundled sample text was recognized."
            else "FAIL — OCR ran, but expected text was missing (recognized: ${text.take(80)})."
        } finally {
            bitmap.recycle()
            withContext(Dispatchers.IO) {
                synchronized(ocrLock) {
                    runCatching { cachedOcrRecognizer?.close() }
                    cachedOcrRecognizer = null
                }
            }
        }
    }

    private suspend fun network(): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://www.google.com/generate_204").get().build()
        var lastFailure = "No response was received."
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext "PASS — internet request completed on attempt ${attempt + 1} (HTTP ${response.code})."
                    lastFailure = "Network endpoint returned HTTP ${response.code}."
                    if (response.code in 400..499) return@withContext "FAIL — $lastFailure"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("DIAGNOSTIC", "network check attempt ${attempt + 1} failed", error)
                lastFailure = error.message ?: error.javaClass.simpleName
            }
            if (attempt == 0) delay(400L)
        }
        "FAIL — network request failed after one retry: $lastFailure."
    }

    private suspend fun deviceStatusDryRun(): String = withContext(Dispatchers.Main) {
        val summary = DeviceStatusProvider.reply(appContext, DeviceStatusProvider.Request.SUMMARY)
        val expected = listOf("Device status", "Storage free:", "RAM free:", "Battery:", "Network:", "Media volume:")
        val missing = expected.filterNot(summary::contains)
        if (missing.isEmpty()) "PASS — storage, RAM, battery, connectivity and media-volume fields were returned. Exact device values are not copied into Diagnostics."
        else "FAIL — device-status summary is missing: ${missing.joinToString(", ")}."
    }

    private fun notificationQueryPhraseSuite(): String {
        val phrases = NotificationQueryRegressionCases.positivePhrases
        val failed = phrases.filter { NotificationQueryParser.parse(it) !is NotificationQueryParser.Outcome.Parsed }
        val filtered = NotificationQueryParser.parse("show unread messages today from com.example.mail") as? NotificationQueryParser.Outcome.Parsed
        val contact = NotificationQueryParser.parse("what did Alex Smith send") as? NotificationQueryParser.Outcome.Parsed
        val okay = failed.isEmpty() && filtered?.query?.unreadOnly == true && filtered.query.todayOnly &&
            filtered.query.fromApp == "com.example.mail" && contact?.query?.contact == "Alex Smith" &&
            NotificationQueryParser.parse("sho calls") is NotificationQueryParser.Outcome.Parsed &&
            NotificationQueryParser.parse("show calls music notification") is NotificationQueryParser.Outcome.Clarify &&
            NotificationQueryParser.parse("show blargh notification") is NotificationQueryParser.Outcome.Rejected
        return if (okay) "PASS — all ${phrases.size} saved positive phrases, unread/today/app filters, contact spelling, typo, ambiguity and reject checks passed locally."
        else "FAIL — notification query self-test failed (${failed.size} of ${phrases.size} phrase cases did not parse)."
    }

    private fun notificationCardPrivacyDryRun(): String {
        val call = NotificationPrivacyFormatter.line("Calls", "Unknown caller", "private diagnostic text")
        val smsHidden = NotificationPrivacyFormatter.line("Messages/SMS", "Alex", "private diagnostic text")
        val smsShown = NotificationPrivacyFormatter.line("Messages/SMS", "Alex", "private diagnostic text", includeMessageBody = true)
        return if (call == "Unknown caller" && smsHidden == "Alex" && smsShown == "Alex: private diagnostic text")
            "PASS — call and SMS cards keep message bodies hidden by default and reveal them only in the explicit preview path; synthetic labels only."
        else "FAIL — notification card privacy formatting did not match the safe defaults."
    }

    private fun overlayPositionDryRun(): String {
        val clamped = OverlayPositionPolicy.clamp(OverlayPosition(Int.MAX_VALUE, Int.MAX_VALUE), 200, 160, 40, 30)
        val invalid = OverlayPositionPolicy.clamp(OverlayPosition(5, 5), 0, 0, 10, 10)
        return if (clamped == OverlayPosition(160, 130) && invalid == OverlayPosition(0, 0))
            "PASS — bubble positioning stayed inside the screen at extreme coordinates and handled an invalid display size; no overlay was started."
        else "FAIL — floating bubble position policy did not stay within its safe bounds."
    }

    private fun volumeCommandDryRun(): String {
        val parsed = VolumeCommandParser.parse("rise volume 30") == VolumeCommand.SetPercent(30) &&
            VolumeCommandParser.parse("volume up") == VolumeCommand.IncreaseOneStep &&
            VolumeCommandParser.parse("set volume to 50 percent") == VolumeCommand.SetPercent(50) &&
            VolumeCommandParser.parse("mute") == VolumeCommand.Mute &&
            VolumeCommandParser.parse("rise volume 101") == VolumeCommand.NotCommand
        val target = VolumeLevelPolicy.target(7, 0, 15, VolumeCommand.SetPercent(50)) == 8 &&
            VolumeLevelPolicy.target(14, 0, 15, VolumeCommand.IncreaseOneStep) == 15 &&
            VolumeLevelPolicy.target(1, 0, 15, VolumeCommand.Mute) == 0 &&
            VolumeLevelPolicy.target(7, 0, 15, VolumeCommand.NotCommand) == null
        return if (parsed && target) "PASS — increase, percentage, mute, invalid-range and media-stream level math passed without changing phone volume."
        else "FAIL — volume parser or bounded media-stream target calculation failed."
    }

    private fun speechRecognizer(): String = if (runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false))
        "PASS — Android speech recognition service is available; offline mode is requested when possible. Last status: ${SpeechDiagnostics.lastStatus} Last error: ${SpeechDiagnostics.lastError}"
    else "FAIL — no Android speech recognizer is installed or available. Last error: ${SpeechDiagnostics.lastError} You can still type requests."

    private fun voskModel(): String {
        val dir = File(appContext.filesDir, "vosk-model-small-en-us-0.15")
        val valid = File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile
        return if (valid) "PASS — optional English Vosk model is present." else "PASS — optional Vosk model is not installed; default Android speech input needs no Vosk download."
    }

    private suspend fun alarmScheduleAndFire(): String = withContext(Dispatchers.IO) {
        val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return@withContext "FAIL — AlarmManager is unavailable."
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms())
            return@withContext "FAIL — exact-alarm access is not granted; enable Alarms & reminders permission in Android settings."
        val prefs = appContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        prefs.edit().remove(DiagnosticAlarmReceiver.KEY_FIRED_AT).apply()
        val fireAt = System.currentTimeMillis() + 60_000L
        val show = PendingIntent.getActivity(appContext, ALARM_SHOW_REQUEST,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val fireIntent = Intent(appContext, DiagnosticAlarmReceiver::class.java).setAction(DiagnosticAlarmReceiver.ACTION_TEST)
        val fire = PendingIntent.getBroadcast(appContext, ALARM_FIRE_REQUEST, fireIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, show), fire)
            CrashLog.record("DIAGNOSTIC", "test AlarmClock scheduled for $fireAt")
        } catch (error: Exception) {
            return@withContext "FAIL — Android rejected setAlarmClock: ${error.message ?: error.javaClass.simpleName}."
        }
        repeat(70) {
            delay(1000)
            val firedAt = prefs.getLong(DiagnosticAlarmReceiver.KEY_FIRED_AT, 0L)
            if (firedAt >= fireAt - 3000L) return@withContext "PASS — setAlarmClock fired at ${java.text.DateFormat.getTimeInstance().format(firedAt)}; test alarm announcement was started. Use Stop on its notification."
        }
        "FAIL — no diagnostic alarm broadcast was received within 70 seconds. Check the app's exact-alarm and Xiaomi battery/autostart settings."
    }

    companion object {
        private const val ALARM_SHOW_REQUEST = 94001
        private const val ALARM_FIRE_REQUEST = 94002
    }
}
