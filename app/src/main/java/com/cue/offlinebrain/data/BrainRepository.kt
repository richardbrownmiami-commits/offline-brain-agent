package com.cue.offlinebrain.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.cue.offlinebrain.accessibility.DeviceActions
import com.cue.offlinebrain.alarm.AlarmScheduler
import com.cue.offlinebrain.alarm.SystemClockAlarmMirror
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.device.DeviceStatusProvider
import com.cue.offlinebrain.device.VolumeCommand
import com.cue.offlinebrain.device.VolumeCommandParser
import com.cue.offlinebrain.engine.IntentEngine
import com.cue.offlinebrain.engine.CompoundCommandParser
import com.cue.offlinebrain.engine.InstalledCommandApp
import com.cue.offlinebrain.engine.SmallTalkRouter
import com.cue.offlinebrain.net.PublicSkills
import com.cue.offlinebrain.notifications.BrainNotificationListener
import com.cue.offlinebrain.notifications.NotificationAccessState
import com.cue.offlinebrain.notifications.NotificationPrivacyFormatter
import com.cue.offlinebrain.notifications.NotificationQueryParser
import com.cue.offlinebrain.notifications.NotificationLastSeenStore
import com.cue.offlinebrain.notifications.PackageLabelResolver
import com.cue.offlinebrain.summary.DocumentSummary
import com.cue.offlinebrain.summary.ExtractiveSummarizer
import com.cue.offlinebrain.summary.DocumentKind
import com.cue.offlinebrain.summary.ResumeDocumentParser
import com.cue.offlinebrain.voice.VoiceSpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.IOException

class BrainRepository(private val context: Context, private val db: BrainDatabase, val settings: SettingsStore) {
    val messages: Flow<List<MessageEntity>> = db.dao().observeLatestMessages()
    val notes: Flow<List<NoteEntity>> = db.dao().observeNotes()
    private val engine = IntentEngine()
    private val smallTalkRouter = SmallTalkRouter()
    private val publicSkills = PublicSkills()
    private val documents = DocumentSummary(context)
    private val summarizer = ExtractiveSummarizer()
    private val resumeParser = ResumeDocumentParser()
    @Volatile private var launcherAppCache: Pair<Long, List<InstalledCommandApp>>? = null
    @Volatile private var lastNotificationContext: NotificationFollowUpContext? = null
    private val READ_ONLY_STATUS_REQUESTS = setOf(
        DeviceStatusProvider.Request.SUMMARY, DeviceStatusProvider.Request.STORAGE, DeviceStatusProvider.Request.RAM,
        DeviceStatusProvider.Request.BATTERY, DeviceStatusProvider.Request.CHARGING, DeviceStatusProvider.Request.NETWORK,
        DeviceStatusProvider.Request.VOLUME, DeviceStatusProvider.Request.BRIGHTNESS, DeviceStatusProvider.Request.PHONE_MODEL,
        DeviceStatusProvider.Request.ANDROID_VERSION, DeviceStatusProvider.Request.UPTIME, DeviceStatusProvider.Request.TEMPERATURE,
        DeviceStatusProvider.Request.INSTALLED_APPS, DeviceStatusProvider.Request.NETWORK_OPERATOR, DeviceStatusProvider.Request.IP_ADDRESS
    )
    private val commandCandidate = Regex("(?i)^\\s*(?:open|launch|start|search|find|look\\s+for|type|write|tap|click|press|scroll|go\\s+back|go\\s+home|go\\s+to|back|home|call|play|send)\\b")

    private enum class NotificationFollowUpKind { WHY, MORE, DETAILS, AGAIN }

    private data class NotificationFollowUpContext(
        val query: NotificationQueryParser.Query,
        val category: String?,
        val resultCount: Int,
        val latestAt: Long?,
        val appName: String?
    )

    suspend fun seedFaqs() {
        db.dao().seedFaqs(listOf(
            FaqEntity(1, "What can you do?", "I can answer simple help questions, fetch weather, headlines, currency rates and Wikipedia summaries, create exact alarms, save notes, and—if you enable Accessibility—perform basic taps and app launches. I use rules stored on the phone.", "help skills capabilities"),
            FaqEntity(2, "Are my messages sent to an AI service?", "Your chat messages stay in the encrypted database on this device. Online lookups go only to the public service you requested.", "privacy offline"),
            FaqEntity(3, "How do I use screen actions?", "Open Settings → Permissions checklist → Accessibility, enable Offline Brain Agent, then ask for a tap or app launch. Android will show that the service can observe screen content.", "accessibility tap open app"),
            FaqEntity(4, "How do I set an alarm?", "Try: ‘set an alarm for 7:30 am tomorrow’ or ‘remind me in 10 minutes’. Exact alarms are scheduled by Android AlarmManager.", "alarm reminder"),
            FaqEntity(5, "Can you summarize a PDF?", "Tap the attachment button to choose a PDF. Text pages and scanned pages can be summarized with source sentences.", "pdf summary document")
        ))
    }

    suspend fun handle(text: String, onStep: (String) -> Unit = {}): BrainReply {
        val normalized = text.trim()
        if (normalized.isBlank()) return BrainReply.Text("Type a request and I'll try a matching local skill.")

        val followUp = notificationFollowUpKind(normalized)
        val previous = lastNotificationContext
        if (followUp != null && previous != null) {
            if (followUp == NotificationFollowUpKind.WHY) return BrainReply.Text(explainPreviousNotificationAnswer(previous))
            val repeatedQuery = when (followUp) {
                NotificationFollowUpKind.AGAIN -> previous.query
                NotificationFollowUpKind.MORE, NotificationFollowUpKind.DETAILS -> previous.query.copy(
                    action = if (previous.query.action == NotificationQueryParser.Action.READ ||
                        previous.query.action == NotificationQueryParser.Action.TELL || !previous.query.contact.isNullOrBlank())
                        previous.query.action else NotificationQueryParser.Action.LIST
                )
                NotificationFollowUpKind.WHY -> previous.query
            }
            return handleNotificationQuestion(normalized, NotificationQueryParser.Outcome.Parsed(repeatedQuery))
        }

        val notificationQuery = NotificationQueryParser.parse(normalized)
        if (shouldRouteNotification(normalized, notificationQuery)) {
            lastNotificationContext = null
            return handleNotificationQuestion(normalized, notificationQuery)
        }
        lastNotificationContext = null

        val volumeCommand = VolumeCommandParser.parse(normalized)
        if (volumeCommand != VolumeCommand.NotCommand) {
            val result = withContext(Dispatchers.Main) { DeviceStatusProvider.applyVolumeCommand(context, volumeCommand) }
            return if (volumeCommand == VolumeCommand.Read) deviceStatusCard("Media volume", result) else BrainReply.Text(result)
        }
        DeviceStatusProvider.classify(normalized)?.let { request ->
            val result = withContext(Dispatchers.Main) { DeviceStatusProvider.reply(context, request, normalized) }
            return if (request in READ_ONLY_STATUS_REQUESTS) deviceStatusCard(statusTitle(request), result) else BrainReply.Text(result)
        }

        return brainReplyFromLegacyText(handleLegacy(normalized, allowAutomation = true, allowLearned = true, onStep = onStep))
    }

    private suspend fun handleLegacy(text: String, allowAutomation: Boolean, allowLearned: Boolean, onStep: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val prefs = settings.settings.first()
        val normalized = text.trim()
        if (normalized.isBlank()) return@withContext "Type a request and I'll try a matching local skill."
        smallTalkRouter.reply(normalized)?.let { return@withContext it }
        if (normalized.startsWith("ask about this document:", ignoreCase = true)) {
            val question = normalized.substringAfter(':', "").trim()
            if (question.isBlank()) return@withContext "Type a question after ‘ask about this document:’."
            val marker = "\n--- CLEANED TEXT ---\n"
            val latestDocument = db.dao().observeLatestMessages().first()
                .firstOrNull { it.role == "assistant" && marker in it.text }
                ?.text?.substringAfter(marker, "")?.take(200_000).orEmpty()
            if (latestDocument.isBlank()) return@withContext "I couldn't find a saved PDF summary to answer from. Attach and summarize a PDF first."
            if (resumeParser.parse(latestDocument).kind == DocumentKind.RESUME) {
                val answer = resumeParser.answer(latestDocument, question)
                return@withContext if (answer.found)
                    "From the saved resume (only details present in its text; matched terms are highlighted):\n${answer.text}"
                else "I couldn't find that detail in the saved resume text, so I won't guess. Try a field such as name, email, phone, skills, education, languages, or a previous employer."
            }
            val answer = summarizer.summarize(latestDocument, maxSentences = 5, focus = question)
            return@withContext if (answer.isBlank()) "I couldn't find enough readable text in the saved document to answer that." else
                "From the saved document (extractive source sentences):\n$answer"
        }
        val notificationQuery = NotificationQueryParser.parse(normalized)
        if (shouldRouteNotification(normalized, notificationQuery)) return@withContext handleNotificationQuestion(normalized, notificationQuery).plainText
        val volumeCommand = VolumeCommandParser.parse(normalized)
        if (volumeCommand != VolumeCommand.NotCommand) {
            return@withContext withContext(Dispatchers.Main) { DeviceStatusProvider.applyVolumeCommand(context, volumeCommand) }
        }
        DeviceStatusProvider.classify(normalized)?.let { request ->
            return@withContext withContext(Dispatchers.Main) { DeviceStatusProvider.reply(context, request, normalized) }
        }
        if (looksLikeWebUrl(normalized)) return@withContext documents.web(normalized)

        val dryRun = normalized.startsWith("dry run ", ignoreCase = true)
        val commandText = if (dryRun) normalized.drop(8).trim() else normalized
        if (commandCandidate.containsMatchIn(commandText)) {
            val parsed = CompoundCommandParser.parse(commandText, installedCommandApps())
            if (parsed.recognized) {
                if (parsed.error != null) return@withContext "I couldn't safely plan that command: ${parsed.error}"
                val plan = parsed.plan ?: return@withContext "I couldn't make a safe plan for that command. Nothing was run."
                if (dryRun) return@withContext "Planned steps (dry run — nothing was opened or changed):\n${plan.plainText()}"
                onStep("Planning ${plan.actions.size} command step(s)")
                return@withContext DeviceActions.executeCompound(plan) { progress -> onStep(progress) }
            }
        }

        val request = contextualize(normalized, prefs)
        val trained = if (allowLearned) LearnedPhraseMatcher.firstMatch(request, prefs.learnedPhrases) else null
        if (trained != null) return@withContext runTaughtPhrase(trained, request, prefs, onStep)
        if (allowAutomation) {
            val rule = db.dao().enabledAutomationRules().firstOrNull { request.contains(it.triggerPhrase, ignoreCase = true) }
            if (rule != null && !rule.actionText.equals(normalized, ignoreCase = true)) {
                CrashLog.record("AUTOMATION", "user rule matched id=${rule.id}")
                return@withContext handleLegacy(rule.actionText, allowAutomation = false, allowLearned = true, onStep = onStep)
            }
        }

        if (normalized.contains("otp", true) && (normalized.contains("what", true) || normalized.contains("code", true))) {
            val access = notificationPermissionStatus()
            val start = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
            val found = db.dao().searchRecentOtpNotifications(start)
            return@withContext withNotificationAccessNote(formatNotifications(found, "No matching saved code notifications were found."), access)
        }

        val match = engine.classify(request, prefs.customPhrase, prefs.defaultCity)
        when (match.name) {
            "greeting" -> "Hi. I work from rules stored on your phone. Try a weather lookup, ‘latest news’, ‘who is Ada Lovelace’, a currency conversion, an alarm, or a note."
            "help" -> "I can route clear requests to weather, RSS news, Wikipedia, currency, alarms, notes, PDF/web summaries, and basic accessibility actions. If a rule or source doesn't match, I'll say so."
            "weather" -> {
                val city = match.city ?: prefs.defaultCity
                settings.rememberContext("weather", city)
                requestWithCache(WorkerCacheKeys.weather(city)) { publicSkills.weather(city) }
            }
            "news" -> {
                settings.rememberContext("news")
                requestWithCache(WorkerCacheKeys.headlines(prefs.newsFeed)) { publicSkills.headlines(prefs.newsFeed) }
            }
            "wiki" -> if (match.query.isNullOrBlank()) "Tell me what person or topic to look up, such as ‘who is Ada Lovelace’." else publicSkills.wikipedia(match.query)
            "currency" -> {
                val m = Regex("(?i)(?:(\\d+(?:\\.\\d+)?)\\s*)?([A-Z]{3})\\s+(?:to|in)\\s+([A-Z]{3})").find(request)
                    ?: Regex("(?i)convert\\s+(\\d+(?:\\.\\d+)?)\\s+([A-Z]{3})\\s+to\\s+([A-Z]{3})").find(request)
                if (m == null) "Try ‘convert 100 USD to INR’." else {
                    val amount = m.groupValues.getOrNull(1).orEmpty().takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 1.0
                    val from = m.groupValues.getOrNull(2).orEmpty()
                    val to = m.groupValues.getOrNull(3).orEmpty()
                    if (from.isBlank() || to.isBlank()) "Try ‘convert 100 USD to INR’." else publicSkills.currency(from, to, amount)
                }
            }
            "alarm" -> {
                val slot = match.alarm ?: return@withContext "What time should I set it for? Try ‘set alarm for 7:30 am tomorrow’ or ‘remind me in 10 minutes’."
                settings.rememberContext("alarm", alarmRequest = request)
                val alarm = AlarmEntity(label = "Reminder: ${slot.label}", triggerAt = slot.triggerAtMillis, cron = slot.cron)
                val id = db.dao().addAlarm(alarm)
                val scheduled = AlarmScheduler.schedule(context, alarm.copy(id = id))
                val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(slot.triggerAtMillis)
                val mirrorNote = if (scheduled && slot.cron == null && prefs.mirrorAlarmsToSystemClock)
                    withContext(Dispatchers.Main) { SystemClockAlarmMirror.addOneTime(context, slot.triggerAtMillis, alarm.label) } else ""
                if (scheduled) "Reminder set for $whenText${if (slot.cron != null) " (repeats)" else ""}. Android will announce it with alarm sound and speech.$mirrorNote"
                else "I saved the reminder for $whenText, but Android did not accept the alarm. Open Settings → Permissions checklist and check exact-alarm access and Xiaomi background settings."
            }
            "note" -> {
                val noteText = request.replace(Regex("(?i)^(please\\s+)?(remember|note|todo|to-do|task)(\\s+that|\\s+to)?\\s*"), "").trim()
                if (noteText.isBlank()) "Tell me what to remember, for example ‘remember buy milk’." else {
                    db.dao().addNote(NoteEntity(text = noteText.take(1000)))
                    "Saved a note: “${noteText.take(180)}”. It stays in the encrypted database on this device."
                }
            }
            "open_app", "tap" -> withContext(Dispatchers.Main) { DeviceActions.execute(request) }
            "summary" -> {
                val webUrl = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(request)?.value
                if (webUrl != null) documents.web(webUrl) else "Tap the paperclip to choose a PDF, or paste a full web-page URL. Text and scanned PDF pages can be summarized locally."
            }
            "custom" -> {
                if (prefs.customApiUrl.isBlank()) "Your custom phrase matched, but there is no API URL saved yet. Add one in Settings." else {
                    val headers = settings.customApiHeaders().lines().mapNotNull { line -> val ix = line.indexOf(':'); if (ix > 0) line.substring(0, ix).trim() to line.substring(ix + 1).trim() else null }.toMap()
                    requestWithCache(WorkerCacheKeys.customApi(prefs.customApiUrl, prefs.customJsonPath, headers)) { publicSkills.customGet(prefs.customApiUrl, headers, prefs.customJsonPath) }
                }
            }
            else -> {
                val genericQuestionWords = setOf("how", "what", "who", "when", "where", "why", "which", "is", "are", "do", "does", "can", "you", "me")
                val terms = engine.tokenize(request).filter { it.length >= 3 && it.lowercase(Locale.ROOT) !in genericQuestionWords }.distinct().take(6)
                val fts = if (terms.size < 2) "" else terms.joinToString(" OR ") { "\"$it\"" }
                val faq = if (fts.isBlank()) emptyList() else try { db.dao().searchFaq(fts) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { CrashLog.record("FAQ", "FAQ search failed", error); emptyList() }
                if (faq.isNotEmpty()) faq.firstOrNull()?.answer.orEmpty()
                else if (Regex("(?i)\\b(what|why|how|who|when|where|which|define|explain)\\b").containsMatchIn(request)) {
                    runCatching { publicSkills.wikipedia(request.replace(Regex("[?!.]$"), "")) }.getOrElse { "I don't know. My local rules did not match, and Wikipedia could not provide a usable answer. Try a clearer request or add a phrase in Settings." }
                } else "I don't know how to handle that with my local rules. Try a built-in skill or add a trigger phrase in Settings."
            }
        }
    }

    private fun contextualize(text: String, prefs: AppSettings): String {
        val tomorrowFollowUp = Regex("(?i)^(?:and\\s+)?tomorrow[?!. ]*$").matches(text)
        if (tomorrowFollowUp && prefs.lastIntent == "alarm") {
            val time = Regex("(?i)\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\b").find(prefs.lastAlarmRequest)?.groupValues?.getOrNull(1)
            if (time != null) return "set alarm for $time tomorrow"
        }
        if (prefs.lastIntent == "weather" && Regex("(?i)^(?:and\\s+)?what about\\s+").containsMatchIn(text)) {
            val city = text.replace(Regex("(?i)^(?:and\\s+)?what about\\s+"), "").trim().trimEnd('?', '.', '!')
            if (city.isNotBlank()) return "weather in ${city.take(80)}"
        }
        if (prefs.lastIntent == "weather" && Regex("(?i)^and\\s+tomorrow\\b").containsMatchIn(text)) {
            return "weather tomorrow in ${prefs.lastCity.ifBlank { prefs.defaultCity }}"
        }
        return text
    }

    private suspend fun runTaughtPhrase(phrase: LearnedPhrase, text: String, prefs: AppSettings, onStep: (String) -> Unit): String = when (phrase.intent) {
        "weather" -> {
            val city = Regex("(?i)\\b(?:in|for|near)\\s+([\\p{L}][\\p{L} .'-]{1,35})").find(text)?.groupValues?.getOrNull(1)?.trim() ?: prefs.defaultCity
            settings.rememberContext("weather", city)
            requestWithCache(WorkerCacheKeys.weather(city)) { publicSkills.weather(city) }
        }
        "news" -> { settings.rememberContext("news"); requestWithCache(WorkerCacheKeys.headlines(prefs.newsFeed)) { publicSkills.headlines(prefs.newsFeed) } }
        "notifications" -> {
            val query = "what notifications did I get"
            handleNotificationQuestion(query, NotificationQueryParser.parse(query)).plainText
        }
        "alarm" -> {
            val request = engine.classify("alarm ${text.replace(phrase.phrase, "", ignoreCase = true)}", defaultCity = prefs.defaultCity)
            val slot = request.alarm ?: return "I learned that phrase for alarms. Add a time, for example ‘${phrase.phrase} at 7 am’."
            val entity = AlarmEntity(label = "Reminder: ${slot.label}", triggerAt = slot.triggerAtMillis, cron = slot.cron)
            val id = db.dao().addAlarm(entity)
            val scheduled = AlarmScheduler.schedule(context, entity.copy(id = id))
            val mirrorNote = if (scheduled && slot.cron == null && prefs.mirrorAlarmsToSystemClock)
                withContext(Dispatchers.Main) { SystemClockAlarmMirror.addOneTime(context, slot.triggerAtMillis, entity.label) } else ""
            if (scheduled) "Reminder set for ${java.text.DateFormat.getDateTimeInstance().format(slot.triggerAtMillis)}.$mirrorNote" else "Android did not accept that alarm. Check exact-alarm access."
        }
        "pdf" -> "I learned that phrase for PDFs. Tap the paperclip and choose a document to summarize."
        "morning" -> {
            val weather = requestWithCache(WorkerCacheKeys.weather(prefs.defaultCity)) { publicSkills.weather(prefs.defaultCity) }
            val news = requestWithCache(WorkerCacheKeys.headlines(prefs.newsFeed)) { publicSkills.headlines(prefs.newsFeed) }
            "Morning check-in · weather for ${prefs.defaultCity}:\n$weather\n\nHeadlines:\n$news"
        }
        "open_app" -> {
            val target = phrase.argument.ifBlank { text.substringAfter(phrase.phrase, "").trim() }
            if (target.isBlank()) "This phrase needs an app name. Edit it and enter the app to open." else
                handleLegacy("open $target", allowAutomation = false, allowLearned = false, onStep = onStep)
        }
        "command", "search" -> {
            val command = phrase.argument.trim()
            if (command.isBlank()) "Edit this phrase and enter one chat command first." else {
                onStep("Running the saved command")
                handleLegacy(command, allowAutomation = false, allowLearned = false, onStep = onStep)
            }
        }
        "device_status" -> {
            onStep("Checking this phone's device status")
            handleLegacy("device status", allowAutomation = false, allowLearned = false, onStep = onStep)
        }
        "note" -> {
            val noteText = phrase.argument.trim()
            if (noteText.isBlank()) "Edit this phrase and enter the note text first." else
                handleLegacy("remember $noteText", allowAutomation = false, allowLearned = false, onStep = onStep)
        }
        "chain" -> {
            val steps = PhraseChainParser.parse(phrase.argument)
            if (steps.isEmpty()) "Edit this phrase and add one chat command per line." else {
                val results = mutableListOf<String>()
                for ((index, command) in steps.withIndex()) {
                    onStep("Phrase chain: step ${index + 1} of ${steps.size}")
                    val result = handleLegacy(command, allowAutomation = false, allowLearned = false, onStep = onStep)
                    results += "${index + 1}. $result"
                    if (MacroRunPolicy.isFailureReply(result)) {
                        results += "Stopped here; later actions were not run."
                        break
                    }
                    if (index < steps.lastIndex) delay(650L)
                }
                results.joinToString("\n").take(6_000)
            }
        }
        "custom_reply" -> phrase.argument.ifBlank { "Edit this phrase and enter the reply text first." }
        "macro" -> {
            val name = phrase.argument.ifBlank { text.substringAfter(phrase.phrase, "").trim() }
            val macro = if (name.isBlank()) null else db.dao().macroByName(name)
            var decodeFailed = false
            val steps = try { macro?.let { TypedMacroSteps.decode(it.steps) }.orEmpty() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                CrashLog.record("AUTOMATION", "learned macro steps could not be decoded", error)
                decodeFailed = true
                emptyList()
            }
            if (decodeFailed) "I couldn't read that saved macro. Open it in the typed editor, check the steps and save it again."
            else if (macro == null || steps.isEmpty()) "I couldn't find a saved macro with usable steps. Edit the phrase and check its macro name." else {
                CrashLog.record("AUTOMATION", "user phrase started typed macro steps=${steps.size}")
                val results = mutableListOf<String>()
                val executor = MacroStepExecutor(
                    findMacro = { id -> db.dao().macroById(id) },
                    runChatCommand = { command ->
                        onStep("${macro.name}: handling a typed request")
                        val answer = handleLegacy(command, allowAutomation = false, allowLearned = false, onStep = onStep)
                        results += answer
                        answer
                    },
                    speakText = { speech -> VoiceSpeechOutput.speakOnce(context, speech) },
                    onProgress = { progress -> onStep("${progress.macroName}: step ${progress.stepIndex + 1} of ${progress.stepCount}") },
                    onError = { error -> CrashLog.record("AUTOMATION", "learned typed macro step raised ${error.javaClass.simpleName}", error) }
                )
                val result = executor.run(macro)
                val completed = results.mapIndexed { index, answer -> "${index + 1}. $answer" }
                if (result.succeeded) "Ran “${macro.name}” through the app's normal request handlers:\n${completed.joinToString("\n")}" else {
                    val at = (result.failedStepIndex ?: 0) + 1
                    "Macro “${macro.name}” stopped at step $at of ${result.stepCount.coerceAtLeast(1)}: ${result.message ?: "the action failed"}. Later steps were not run." +
                        if (completed.isNotEmpty()) "\n${completed.joinToString("\n")}" else ""
                }
            }
        }
        else -> "That taught phrase is not connected to a supported skill."
    }

    private suspend fun requestWithCache(key: String, fetch: () -> String): String = try {
        fetch()
    } catch (error: IOException) {
        val cached = db.dao().workerCache(key)
        if (cached == null || !WorkerCachePolicy.isFresh(cached.updatedAt, System.currentTimeMillis())) throw error
        CrashLog.record("CACHE", "using recent cached response after a live request failed")
        val savedAt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(cached.updatedAt)
        "Live lookup failed. Showing the cached result saved at $savedAt instead:\n\n${cached.content}"
    }

    private suspend fun handleNotificationQuestion(text: String, outcome: NotificationQueryParser.Outcome): BrainReply {
        val permission = notificationPermissionStatus()
        if (permission == false) CrashLog.record("NOTIFICATIONS", "query requested while listener is enabled but not connected; using saved database rows")
        val query = when (outcome) {
            is NotificationQueryParser.Outcome.Parsed -> outcome.query
            is NotificationQueryParser.Outcome.Clarify -> return BrainReply.Text("I found more than one possible notification meaning (${outcome.candidates.sorted().joinToString(", ")}). Try naming one category, such as SMS, Chat, Calls, or Social.")
            is NotificationQueryParser.Outcome.Rejected -> return BrainReply.Text("I couldn't safely understand that notification request (${outcome.reason}). Try “show SMS notifications”, “read system notifications”, or “any new notifications”.")
        }
        val category = query.category?.let(::notificationCategoryName)
        val appRows = if (query.fromApp.isNullOrBlank()) emptyList() else db.dao().notificationPackageCounts()
        val appMatches = if (query.fromApp.isNullOrBlank()) emptyList() else appRows.filter { row ->
            val label = PackageLabelResolver.label(context, row.packageName)
            label.equals(query.fromApp, ignoreCase = true) || row.packageName.equals(query.fromApp, ignoreCase = true) ||
                row.packageName.substringAfterLast('.').equals(query.fromApp, ignoreCase = true)
        }
        if (query.fromApp != null && appMatches.size > 1) {
            val labels = appMatches.take(5).joinToString(", ") { PackageLabelResolver.label(context, it.packageName) }
            return BrainReply.Text("I found more than one app matching “${query.fromApp}”: $labels. Please use the full app name.")
        }
        val targetPackage = appMatches.singleOrNull()?.packageName
        val sinceSeen = if (query.unreadOnly) NotificationLastSeenStore.lastSeen(context, category) else 0L
        val todayStart = if (query.todayOnly) java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis else 0L
        val since = maxOf(sinceSeen, todayStart)
        val unread = query.unreadOnly
        val selectedRows = when {
            query.fromApp != null && targetPackage != null -> db.dao().notificationsFromPackageFiltered(targetPackage, since, unread)
            query.fromApp != null -> emptyList()
            category != null -> db.dao().notificationsByCategoryFiltered(category, since, unread)
            !query.contact.isNullOrBlank() -> db.dao().searchNotificationsFiltered(query.contact, since, unread)
            else -> db.dao().recentNotificationsFiltered(since, unread)
        }
        val found = selectedRows.filter { row ->
            (targetPackage == null || row.packageName == targetPackage) &&
                (query.contact.isNullOrBlank() || row.title.contains(query.contact, true) || row.text.contains(query.contact, true))
        }
        NotificationLastSeenStore.markSeen(context, category, System.currentTimeMillis())
        val counts = db.dao().notificationCategoryCounts()
        val categoriesAvailable = counts.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.category} ${it.count}" } ?: "none yet"
        val appName = targetPackage?.let { PackageLabelResolver.label(context, it) }
        val empty = when {
            category != null -> "No $category notifications saved yet. You have: $categoriesAvailable."
            query.fromApp != null && appName == null -> "I couldn't match “${query.fromApp}” to an app with saved notifications. You have: $categoriesAvailable."
            appName != null -> "No saved notifications from $appName were found. You have: $categoriesAvailable."
            !query.contact.isNullOrBlank() -> "No saved notifications mentioning ${query.contact} were found. You have: $categoriesAvailable."
            else -> "No saved notifications yet. You have: $categoriesAvailable."
        }
        val includeMessageBody = query.action == NotificationQueryParser.Action.READ ||
            query.action == NotificationQueryParser.Action.TELL || !query.contact.isNullOrBlank()
        val prefs = settings.settings.first()
        val notes = buildList {
            if (found.isEmpty()) add(empty)
            when (permission) {
                true -> Unit
                false -> add("Android's notification listener is disconnected. These results come from saved local records; check Settings → Diagnostics.")
                null -> add("Notification Access is off. I searched only saved local records; enable it in Settings to collect new notifications.")
            }
            if (!prefs.notificationLiveCaptureEnabled) add("Live capture is off; these are saved local records only.")
        }
        val note = notes.distinct().joinToString("\n\n")
        val title = when {
            category != null -> "$category notifications"
            appName != null -> "Notifications from $appName"
            !query.contact.isNullOrBlank() -> "Notifications mentioning ${query.contact}"
            else -> "Saved notifications"
        }
        val latestAt = found.maxOfOrNull { it.postedAt }
        val unreadCount = found.count { !it.isRead }
        val categoryCounts = if (category == null) counts.map { ReplyCategoryCount(it.category, it.count) }
            else found.groupingBy { it.category }.eachCount().map { ReplyCategoryCount(it.key, it.value) }.sortedByDescending { it.count }
                .ifEmpty { listOf(ReplyCategoryCount(category, 0)) }
        lastNotificationContext = NotificationFollowUpContext(query, category, found.size, latestAt, appName)

        if (query.action in setOf(NotificationQueryParser.Action.SHOW, NotificationQueryParser.Action.ANY,
                NotificationQueryParser.Action.OPEN, NotificationQueryParser.Action.WHAT_IN)) {
            return BrainReply.NotificationSummaryCard(title, category, found.size, unreadCount, latestAt, categoryCounts, note)
        }

        val grouped = found.groupBy { row ->
            if (row.category == "Music and media") "media\u0000${row.packageName}\u0000${row.title}"
            else listOf(row.packageName, row.title, row.text, row.category).joinToString("\u0000")
        }.values.mapNotNull { group -> group.maxByOrNull { it.postedAt }?.let { latest -> latest to group.size } }
            .sortedByDescending { it.first.postedAt }.take(12)
        val items = grouped.map { (row, repeatedCount) ->
            ReplyNotificationItem(
                id = row.id,
                packageName = row.packageName,
                title = row.title.ifBlank { PackageLabelResolver.label(context, row.packageName) },
                text = if (includeMessageBody) row.text else "",
                category = row.category,
                postedAt = row.postedAt,
                groupName = row.groupName,
                duplicateCount = repeatedCount,
                isRead = row.isRead
            )
        }
        return BrainReply.NotificationList(title, category, found.size, unreadCount, latestAt, categoryCounts, items, note)
    }

    private fun notificationCategoryName(category: NotificationQueryParser.Category): String = when (category) {
        NotificationQueryParser.Category.MESSAGES_SMS -> "Messages/SMS"
        NotificationQueryParser.Category.CHAT -> "Chat"
        NotificationQueryParser.Category.CALLS -> "Calls"
        NotificationQueryParser.Category.SYSTEM -> "System"
        NotificationQueryParser.Category.MUSIC_AND_MEDIA -> "Music and media"
        NotificationQueryParser.Category.SOCIAL -> "Social"
        NotificationQueryParser.Category.EMAIL -> "Email"
        NotificationQueryParser.Category.WEATHER -> "Weather"
        NotificationQueryParser.Category.SHOPPING -> "Shopping"
        NotificationQueryParser.Category.FOOD -> "Food"
        NotificationQueryParser.Category.GAMES -> "Games"
        NotificationQueryParser.Category.FINANCE -> "Finance"
    }

    private fun relativeNotificationTime(atMillis: Long): String {
        val age = (System.currentTimeMillis() - atMillis).coerceAtLeast(0L)
        val minutes = age / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} hr ago"
            else -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(atMillis))
        }
    }

    private fun withNotificationAccessNote(result: String, access: Boolean?): String = when (access) {
        true -> result
        false -> "$result\n\nNotification Access is enabled, but Android's listener is disconnected. I did not start a rebind from this query; check Settings → Diagnostics."
        null -> "$result\n\nNotification Access is off. I searched only saved local records; enable it in Settings to collect new notifications."
    }

    /** null means Android permission is off; false means enabled but the listener is not connected yet. */
    private fun notificationPermissionStatus(): Boolean? {
        val enabled = runCatching { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
            .onFailure { CrashLog.record("NOTIFICATIONS", "listener permission query failed", it) }.getOrDefault(false)
        if (!enabled) return null
        if (NotificationAccessState.connected) return true
        return false
    }

    private fun notificationFollowUpKind(value: String): NotificationFollowUpKind? {
        val normalized = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return when (normalized) {
            "why", "why is that", "why did you say that", "explain", "explain that" -> NotificationFollowUpKind.WHY
            "more", "tell me more", "more details" -> NotificationFollowUpKind.MORE
            "details", "show details", "give me details", "full details" -> NotificationFollowUpKind.DETAILS
            "again", "repeat", "do that again", "say that again", "show that again" -> NotificationFollowUpKind.AGAIN
            else -> null
        }
    }

    private fun explainPreviousNotificationAnswer(previous: NotificationFollowUpContext): String {
        val subject = previous.category?.let { "$it notifications" }
            ?: previous.appName?.let { "notifications from $it" }
            ?: previous.query.contact?.let { "notifications mentioning $it" }
            ?: "saved notifications"
        val countText = if (previous.resultCount == 0) "I didn't find a matching saved item" else
            "I found ${previous.resultCount} matching saved item${if (previous.resultCount == 1) "" else "s"}"
        val filters = buildList {
            if (previous.query.unreadOnly) add("unread only")
            if (previous.query.todayOnly) add("today only")
            previous.query.fromApp?.takeIf(String::isNotBlank)?.let { add("from $it") }
        }
        val filterText = filters.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " with the filter $it" }.orEmpty()
        val latestText = previous.latestAt?.let {
            " The newest matching item was saved ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))}."
        }.orEmpty()
        return "$countText for $subject$filterText in the notifications already saved on this phone.$latestText I didn't use Wikipedia or search the web, and notification data can't tell me why someone sent an alert."
    }

    private fun statusTitle(request: DeviceStatusProvider.Request): String = when (request) {
        DeviceStatusProvider.Request.SUMMARY -> "Device status"
        DeviceStatusProvider.Request.STORAGE -> "Storage"
        DeviceStatusProvider.Request.RAM -> "Memory"
        DeviceStatusProvider.Request.BATTERY -> "Battery"
        DeviceStatusProvider.Request.CHARGING -> "Charging"
        DeviceStatusProvider.Request.NETWORK -> "Network"
        DeviceStatusProvider.Request.VOLUME -> "Media volume"
        DeviceStatusProvider.Request.BRIGHTNESS -> "Screen brightness"
        DeviceStatusProvider.Request.PHONE_MODEL -> "Phone model"
        DeviceStatusProvider.Request.ANDROID_VERSION -> "Android version"
        DeviceStatusProvider.Request.UPTIME -> "Phone uptime"
        DeviceStatusProvider.Request.TEMPERATURE -> "Battery temperature"
        DeviceStatusProvider.Request.INSTALLED_APPS -> "Installed apps"
        DeviceStatusProvider.Request.NETWORK_OPERATOR -> "Mobile-network operator"
        DeviceStatusProvider.Request.IP_ADDRESS -> "Local network address"
        else -> "Device status"
    }

    private fun deviceStatusCard(title: String, text: String): BrainReply.DeviceStatusCard {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
            .filterNot { it.equals("Device status", ignoreCase = true) }
        val rows = lines.map { line ->
            val pieces = line.split(':', limit = 2)
            val label = pieces.firstOrNull().orEmpty().trim().ifBlank { title }
            val value = pieces.getOrNull(1)?.trim()?.trimEnd('.') ?: line.trim().trimEnd('.')
            DeviceStatusRow(label, value, statusProgressPercent(value))
        }
        return BrainReply.DeviceStatusCard(title, rows.ifEmpty { listOf(DeviceStatusRow(title, text.trim())) })
    }

    private fun statusProgressPercent(value: String): Int? {
        Regex("(\\d{1,3})\\s*%").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        val quantities = Regex("(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB|TB)", RegexOption.IGNORE_CASE)
            .findAll(value).take(2).mapNotNull { match ->
                val amount = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val unit = match.groupValues[2].uppercase(Locale.ROOT)
                val scale = when (unit) { "B" -> 1.0; "KB" -> 1_000.0; "MB" -> 1_000_000.0; "GB" -> 1_000_000_000.0; else -> 1_000_000_000_000.0 }
                amount * scale
            }.toList()
        if (quantities.size >= 2 && quantities[1] > 0.0) return ((quantities[0] / quantities[1]) * 100).toInt().coerceIn(0, 100)
        val pair = Regex("(\\d+(?:\\.\\d+)?)\\s+of\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(value)
            ?: return null
        val current = pair.groupValues[1].toDoubleOrNull() ?: return null
        val maximum = pair.groupValues[2].toDoubleOrNull() ?: return null
        return if (maximum > 0.0) ((current / maximum) * 100).toInt().coerceIn(0, 100) else null
    }

    suspend fun savedNotificationCount(): Int = withContext(Dispatchers.IO) { db.dao().notificationRowCount() }
    suspend fun pruneLocalHistory(before: Long) = withContext(Dispatchers.IO) {
        db.dao().deleteNotificationsBefore(before)
        db.dao().deleteClipboardBefore(before)
    }
    suspend fun saveBackgroundCache(key: String, content: String) = withContext(Dispatchers.IO) {
        db.dao().putWorkerCache(WorkerCacheEntity(key, content.take(4_000)))
    }

    private fun formatNotifications(found: List<NotificationEntity>, emptyMessage: String, includeMessageBody: Boolean = false): String = if (found.isEmpty()) emptyMessage else {
        val counts = found.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }.take(5)
            .joinToString(", ") { "${it.first}: ${it.second}" }
        "Saved notifications (local database; $counts):\n" + found.take(8).joinToString("\n") { row ->
            val sender = row.title.ifBlank { row.packageName.substringAfterLast('.') }
            val detail = NotificationPrivacyFormatter.line(row.category, sender, row.text, includeMessageBody)
            val group = row.groupName.takeIf { it.isNotBlank() && row.category == "Chat" }?.let { "$it — " }.orEmpty()
            "• $group$detail"
        }
    }

    private fun shouldRouteNotification(text: String, outcome: NotificationQueryParser.Outcome): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (Regex("\\bnotifications?\\b").containsMatchIn(lower)) return true
        if (commandCandidate.containsMatchIn(text)) return false
        val hasAction = Regex("(?i)\\b(show|sho|read|tell|list|what|any|open|new|unread|latest)\\b").containsMatchIn(text)
        val hasCategory = Regex("(?i)\\b(text|texts|sms|message|messages|chat|whatsapp|telegram|call|calls|missed|system|android|phone|music|media|youtube|social|email|weather|shopping|food|games|finance)\\b").containsMatchIn(text)
        return when (outcome) {
            is NotificationQueryParser.Outcome.Parsed -> outcome.query.contact != null || (hasAction && hasCategory)
            is NotificationQueryParser.Outcome.Clarify -> hasAction && hasCategory
            is NotificationQueryParser.Outcome.Rejected -> hasAction && hasCategory
        }
    }

    suspend fun summarizePdf(uri: Uri, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) { documents.pdf(uri, onProgress) }
    suspend fun summarizeWebUrl(url: String): String = withContext(Dispatchers.IO) { documents.web(url) }

    private fun installedCommandApps(): List<InstalledCommandApp> {
        val now = System.currentTimeMillis()
        launcherAppCache?.takeIf { now - it.first < 60_000L }?.let { return it.second }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { info ->
                    val packageName = info.activityInfo?.packageName.orEmpty()
                    if (packageName.isBlank()) return@mapNotNull null
                    val label = try { info.loadLabel(context.packageManager).toString().trim() }
                    catch (error: Exception) { CrashLog.record("COMMAND", "launcher app label unavailable for $packageName", error); "" }
                    label.takeIf(String::isNotBlank)?.let { InstalledCommandApp(it.take(100), packageName.take(180)) }
                }
                .distinctBy { it.packageName }
                .take(300)
            launcherAppCache = now to apps
            apps
        } catch (error: Exception) {
            CrashLog.record("COMMAND", "installed launcher app list could not be read", error)
            emptyList()
        }
    }

    private fun looksLikeWebUrl(value: String) = value.startsWith("http://", true) || value.startsWith("https://", true)
}
