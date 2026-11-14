package com.cue.offlinebrain.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavHostController
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.AutomationRuleEntity
import com.cue.offlinebrain.data.ClipboardEntryEntity
import com.cue.offlinebrain.data.MacroEntity
import com.cue.offlinebrain.data.MacroStepDraft
import com.cue.offlinebrain.data.MacroStepEditorModel
import com.cue.offlinebrain.data.MacroStepKind
import com.cue.offlinebrain.data.TypedMacroStep
import com.cue.offlinebrain.data.TypedMacroSteps
import com.cue.offlinebrain.data.PhraseChainParser
import com.cue.offlinebrain.data.NotificationEntity
import com.cue.offlinebrain.data.PinLockStore
import com.cue.offlinebrain.data.shouldStoreNotification
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.notifications.NotificationReplyRegistry
import com.cue.offlinebrain.notifications.NotificationAccessState
import com.cue.offlinebrain.notifications.NotificationCategory
import com.cue.offlinebrain.notifications.NotificationDecisionReason
import com.cue.offlinebrain.notifications.NotificationPipelineTelemetry
import com.cue.offlinebrain.notifications.NotificationTestCapture
import com.cue.offlinebrain.notifications.NotificationPrivacyFormatter
import com.cue.offlinebrain.notifications.PackageLabelResolver
import com.cue.offlinebrain.voice.VoiceSpeechOutput
import com.cue.offlinebrain.ui.BrainViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val Dark = Color(0xFF101118)
private val CardDark = Color(0xFF1A1B25)
private val MutedText = Color(0xFFAAA9B6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureScaffold(title: String, onBack: () -> Unit, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(
        containerColor = Dark,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { CenterAlignedTopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Dark)) },
        content = content
    )
}

@Composable
fun FeatureCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun PhraseTeachingScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var intent by rememberSaveable { mutableStateOf("weather") }
    var argument by rememberSaveable { mutableStateOf("") }
    var editingPhrase by rememberSaveable { mutableStateOf<String?>(null) }
    var wizardStep by rememberSaveable { mutableStateOf(1) }
    var testInput by rememberSaveable { mutableStateOf("") }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    val choices = listOf("weather" to "Weather", "news" to "News", "alarm" to "Alarm", "notifications" to "Notifications", "device_status" to "Device status", "open_app" to "Open app", "search" to "Search", "pdf" to "PDF", "note" to "Notes", "macro" to "Macro", "custom_reply" to "Custom reply", "command" to "Any command", "chain" to "Action chain")
    fun sampleRequest(): String = when (intent) {
        "weather" -> "${phrase.trim()} in Delhi"
        "news" -> phrase.trim()
        "notifications" -> "${phrase.trim()} from WhatsApp"
        "alarm" -> "${phrase.trim()} at 7 am"
        "open_app" -> "${phrase.trim()} ${argument.ifBlank { "WhatsApp" }}"
        "macro" -> "${phrase.trim()} ${argument.ifBlank { "Morning check-in" }}"
        "device_status" -> phrase.trim().ifBlank { "device status" }
        else -> phrase.trim()
    }
    fun actionPreview(): String = when (intent) {
        "command", "search" -> "1. ${argument.ifBlank { "Enter a chat command first." }}"
        "chain" -> PhraseChainParser.preview(argument)
        "device_status" -> "Read local phone status: storage, RAM, battery, network and volume."
        "note" -> "Save this note on the phone: ${argument.ifBlank { "Enter note text first." }}"
        "macro" -> "Run saved macro: ${argument.ifBlank { "Enter a macro name first." }}"
        "custom_reply" -> "Reply with: ${argument.ifBlank { "Enter reply text first." }}"
        "open_app" -> "Open app: ${argument.ifBlank { "Enter an app name first." }}"
        else -> "Run the built-in ${choices.firstOrNull { it.first == intent }?.second ?: "chat"} skill using the usual request handler."
    }
    FeatureScaffold("Teach a phrase", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Help · how phrase rules work") {
                Text("This is a fixed word rule, not a similar-question search. Example: when you say “good morning”, the Morning template gets weather and headlines; you can change or delete the rule later.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
            }
            FeatureCard("Step $wizardStep of 3 · ${listOf("When I say…", "Do this…", "Test and save").getOrElse((wizardStep - 1).coerceIn(0, 2)) { "When I say…" }}") {
                when (wizardStep) {
                    1 -> {
                        OutlinedTextField(phrase, { phrase = it; testResult = null }, Modifier.fillMaxWidth(), label = { Text("When I say…") }, placeholder = { Text("Example: weather please") }, singleLine = true)
                        Text("The phrase is checked as words inside the request, ignoring upper/lower case.", color = MutedText, fontSize = 12.sp)
                        Button(onClick = { wizardStep = 2 }, modifier = Modifier.fillMaxWidth(), enabled = phrase.trim().length >= 2) { Text("Choose what to do") }
                    }
                    2 -> {
                        Text("Do this…", color = MutedText, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { (value, label) -> OutlinedButton(onClick = { intent = value; argument = ""; testResult = null }) { Text(if (intent == value) "✓ $label" else label) }
                            }
                        }
                        Text("Pick a built-in skill, or choose Any command and type a supported request. Use Action chain for one action per line.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                        Button(onClick = { testInput = sampleRequest(); testResult = null; wizardStep = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Test this phrase") }
                    }
                    else -> {
                        Text("This test checks only the local word match. It will not set an alarm, open an app or send a request.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                        if (intent == "open_app") OutlinedTextField(argument, { argument = it; testResult = null }, Modifier.fillMaxWidth(), label = { Text("App name to open") }, singleLine = true)
                        if (intent == "macro") OutlinedTextField(argument, { argument = it; testResult = null }, Modifier.fillMaxWidth(), label = { Text("Saved macro name") }, singleLine = true)
                        if (intent == "custom_reply") OutlinedTextField(argument, { argument = it; testResult = null }, Modifier.fillMaxWidth(), label = { Text("Reply text") }, minLines = 2)
                        if (intent in setOf("command", "search")) OutlinedTextField(argument, { argument = it.take(500); testResult = null }, Modifier.fillMaxWidth(), label = { Text("Type any chat command") }, placeholder = { Text("Example: open Chrome and search cats") }, minLines = 2)
                        if (intent == "chain") OutlinedTextField(argument, { argument = it.take(5_000); testResult = null }, Modifier.fillMaxWidth(), label = { Text("One chat command per line") }, placeholder = { Text("device status\nset an alarm for 6 am\nopen Do Not Disturb settings") }, minLines = 3)
                        if (intent == "note") OutlinedTextField(argument, { argument = it.take(1_000); testResult = null }, Modifier.fillMaxWidth(), label = { Text("Note text to save") }, minLines = 2)
                        if (intent == "device_status") Text("This runs the local Device Status skill when you say the phrase.", color = MutedText, fontSize = 12.sp)
                        OutlinedTextField(testInput, { testInput = it; testResult = null }, Modifier.fillMaxWidth(), label = { Text("Example request to test") }, minLines = 2)
                        testResult?.let { Text(it, color = if (it.startsWith("MATCH")) MaterialTheme.colorScheme.secondary else Color(0xFFFFC978), fontSize = 13.sp) }
                        if (testResult?.startsWith("MATCH") == true) Text("Planned action (dry run only; nothing was opened, saved or sent):\n${actionPreview()}", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { wizardStep = 2 }, modifier = Modifier.weight(1f)) { Text("Back") }
                            Button(onClick = { testResult = if (testInput.contains(phrase.trim(), ignoreCase = true)) "MATCH · these words are found by the fixed rule." else "NO MATCH · add the phrase words to this example." }, modifier = Modifier.weight(1f)) { Text("Test") }
                        }
                        Button(onClick = {
                            val oldPhrase = editingPhrase
                            vm.setting {
                                if (oldPhrase != null && !oldPhrase.equals(phrase.trim(), ignoreCase = true)) deleteLearnedPhrase(oldPhrase)
                                addLearnedPhrase(phrase, intent, argument)
                            }
                            editingPhrase = null; phrase = ""; argument = ""; testInput = ""; testResult = null; wizardStep = 1
                            vm.setToast("Phrase rule saved on this phone.")
                        }, modifier = Modifier.fillMaxWidth(), enabled = testResult?.startsWith("MATCH") == true && when (intent) {
                            "open_app", "macro", "custom_reply", "command", "search", "note" -> argument.isNotBlank()
                            "chain" -> PhraseChainParser.parse(argument).isNotEmpty()
                            else -> true
                        }) { Text("Save phrase") }
                    }
                }
                if (editingPhrase != null) TextButton(onClick = { editingPhrase = null; phrase = ""; argument = ""; testInput = ""; testResult = null; wizardStep = 1 }) { Text("Cancel edit") }
            }
            FeatureCard("Ready-made templates · add with one tap") {
                val templates = listOf("good morning" to "morning", "weather please" to "weather", "latest headlines" to "news", "my notifications" to "notifications", "remind me" to "alarm")
                templates.forEach { (template, destination) ->
                    OutlinedButton(onClick = {
                        vm.setting { addLearnedPhrase(template, destination) }
                        vm.setToast("Added “$template”. You can edit it below.")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Add “$template” → ${if (destination == "morning") "weather + headlines" else destination}") }
                }
            }
            Text("Saved phrases", fontWeight = FontWeight.SemiBold)
            if (settings.learnedPhrases.isEmpty()) Text("No phrases taught yet.", color = MutedText)
            settings.learnedPhrases.forEach { learned ->
                FeatureCard(learned.phrase) {
                    Text("Routes to: ${if (learned.intent == "morning") "weather + headlines" else learned.intent} · fixed phrase matching", color = MutedText, fontSize = 13.sp)
                    TextButton(onClick = { editingPhrase = learned.phrase; phrase = learned.phrase; intent = learned.intent; argument = learned.argument; testInput = sampleRequest(); testResult = null; wizardStep = 1 }) { Text("Edit") }
                    TextButton(onClick = { vm.setting { deleteLearnedPhrase(learned.phrase) } }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun AutomationScreen(vm: BrainViewModel, onBack: () -> Unit) {
    val rules by vm.automationRules.collectAsStateWithLifecycle()
    val macros by vm.macros.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val macroFailure by vm.macroRunFailure.collectAsStateWithLifecycle()
    val recording by vm.macroRecording.collectAsStateWithLifecycle()
    val recordingName by vm.recordingMacroName.collectAsStateWithLifecycle()
    val recordedSteps by vm.recordedMacroSteps.collectAsStateWithLifecycle()
    var trigger by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("") }
    var macroName by rememberSaveable { mutableStateOf("") }
    var ruleStep by rememberSaveable { mutableStateOf(1) }
    var wizardMacroName by rememberSaveable { mutableStateOf("") }
    var macroPayload by rememberSaveable { mutableStateOf("") }
    var macroDrafts by remember { mutableStateOf<List<MacroStepDraft>>(emptyList()) }
    var macroKindName by rememberSaveable { mutableStateOf(MacroStepKind.RUN_CHAT_COMMAND.name) }
    var macroKindMenu by rememberSaveable { mutableStateOf(false) }
    var macroKindMenuFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedNestedMacroId by rememberSaveable { mutableStateOf<Long?>(null) }
    var nestedMacroMenu by rememberSaveable { mutableStateOf(false) }
    var nestedMacroMenuFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var tutorialStage by rememberSaveable { mutableStateOf(0) }
    var macroWizardStep by rememberSaveable { mutableStateOf(1) }
    var macroToRun by remember { mutableStateOf<MacroEntity?>(null) }
    var macroToDelete by remember { mutableStateOf<MacroEntity?>(null) }
    var editingMacroId by rememberSaveable { mutableStateOf<Long?>(null) }
    var macroStepBounds by remember { mutableStateOf<Map<Long, Pair<Float, Float>>>(emptyMap()) }
    val latestMacroDrafts = rememberUpdatedState(macroDrafts)
    val latestMacroStepBounds = rememberUpdatedState(macroStepBounds)
    val addKind = MacroStepKind.values().firstOrNull { it.name == macroKindName } ?: MacroStepKind.RUN_CHAT_COMMAND
    val validationError = MacroStepEditorModel.validate(macroDrafts, macros, editingMacroId)
    val duplicateMacroName = macros.any { it.name.equals(wizardMacroName.trim(), ignoreCase = true) && it.id != editingMacroId }
    val canAddMacroDraft = macroDrafts.size < TypedMacroSteps.MAX_STEPS && when {
        addKind.needsText -> macroPayload.isNotBlank()
        addKind == MacroStepKind.WAIT -> macroPayload.toIntOrNull()?.let { it in 0..300 } == true
        addKind.needsMacro -> selectedNestedMacroId?.let { id -> macros.any { it.id == id && id != editingMacroId } } == true
        else -> true
    }
    fun resetMacroEditor() {
        editingMacroId = null; wizardMacroName = ""; macroPayload = ""; macroDrafts = emptyList()
        macroWizardStep = 1; selectedNestedMacroId = null; macroKindName = MacroStepKind.RUN_CHAT_COMMAND.name
    }
    fun addMacroDraft(afterIndex: Int = macroDrafts.lastIndex) {
        if (!canAddMacroDraft) return
        val draft = MacroStepEditorModel.newDraft(addKind.build(macroPayload, selectedNestedMacroId))
        macroDrafts = MacroStepEditorModel.insertAfter(macroDrafts, afterIndex, draft)
        macroPayload = ""
    }
    fun editMacro(macro: MacroEntity) {
        try {
            macroDrafts = MacroStepEditorModel.drafts(macro.steps)
            editingMacroId = macro.id; wizardMacroName = macro.name; macroWizardStep = 1; macroPayload = ""
        } catch (error: Exception) {
            CrashLog.record("AUTOMATION", "typed macro editor could not decode id=${macro.id}", error)
            vm.setToast("These saved steps could not be opened. Keep the original macro and check its saved data.")
        }
    }
    FeatureScaffold("Automations & macros", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Help · how macros work") {
                Text("A macro is a list of actions that runs in order after you review it. Example: “Open WhatsApp”, “Tap Search”, then “Type Mom”. While recording, accessible labels and text you type stay in memory until Stop; they are saved only if you save the macro. No screenshots are saved.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Text("Accessibility must be on to capture other apps. Fields marked as passwords are skipped, but other private text may be recorded: never record passwords, codes or sensitive screens. Android may block secure screens.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (!appSettings.macroTutorialSeen && tutorialStage < 3) FeatureCard("Three-step macro tutorial · ${tutorialStage.coerceAtLeast(1)} of 3") {
                when (tutorialStage) {
                    0 -> {
                        Text("1. Choose typed steps. 2. Review their order. 3. Save only after checking what they do. This local example opens Chrome, waits two seconds, then requests a search. It will not run until you explicitly choose Run.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                        OutlinedButton(onClick = {
                            wizardMacroName = "Find cats online"
                            macroDrafts = listOf(
                                MacroStepEditorModel.newDraft(TypedMacroStep.OpenApp("Chrome")),
                                MacroStepEditorModel.newDraft(TypedMacroStep.Wait(2)),
                                MacroStepEditorModel.newDraft(TypedMacroStep.RunChatCommand("search cats"))
                            )
                            macroKindName = MacroStepKind.RUN_CHAT_COMMAND.name
                            macroWizardStep = 1
                            tutorialStage = 1
                        }, modifier = Modifier.fillMaxWidth()) { Text("Build the 3-step example") }
                    }
                    1 -> {
                        Text("Step 1 of 3: inspect the typed steps below. Edit a field, move a step up or down, or remove it. Nothing is executed while editing.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                        OutlinedButton(onClick = { macroWizardStep = 2; tutorialStage = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Review the example") }
                    }
                    2 -> {
                        Text("Step 3 of 3: the review page explains the actions. Saving stores the macro but does not run it; web search only occurs if you later press Run. No LLM or cloud AI is used.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                        OutlinedButton(onClick = { vm.markMacroTutorialSeen(); tutorialStage = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Finish tutorial") }
                    }
                }
            }
            FeatureCard("Automation rule · step $ruleStep of 3") {
                Text("A rule checks for your trigger words, then sends the command you chose through the app's normal request handler. Review it before saving.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                when (ruleStep) {
                    1 -> {
                        OutlinedTextField(trigger, { trigger = it }, Modifier.fillMaxWidth(), label = { Text("Words to look for") }, placeholder = { Text("Example: morning briefing") }, singleLine = true)
                        Button(onClick = { ruleStep = 2 }, enabled = trigger.trim().length >= 2, modifier = Modifier.fillMaxWidth()) { Text("Choose the command") }
                    }
                    2 -> {
                        Text("When those words appear, run this request:", color = MutedText, fontSize = 13.sp)
                        OutlinedTextField(action, { action = it }, Modifier.fillMaxWidth(), label = { Text("Command for the app") }, placeholder = { Text("Example: weather in Delhi") }, minLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { ruleStep = 1 }, modifier = Modifier.weight(1f)) { Text("Back") }
                            Button(onClick = { ruleStep = 3 }, enabled = action.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Preview") }
                        }
                    }
                    else -> {
                        Text("If a message contains “${trigger.trim()}”, the app will run:", color = MutedText, fontSize = 13.sp)
                        Text(action.trim(), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { ruleStep = 2 }, modifier = Modifier.weight(1f)) { Text("Back") }
                            Button(onClick = { vm.saveAutomationRule(trigger, action); trigger = ""; action = ""; ruleStep = 1 }, modifier = Modifier.weight(1f)) { Text("Save rule") }
                        }
                    }
                }
            }
            rules.forEach { rule -> RuleCard(rule, onDelete = { vm.deleteAutomationRule(rule.id) }) }
            FeatureCard("Record requests sent in Chat") {
                Text("Press Record, go to Chat and send the requests you want. They are held in memory only until you press Stop. Do not send passwords, private keys or other secrets while recording.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (recording) {
                    Text("Recording: ${recordingName.orEmpty()}", color = Color(0xFFFFC978), fontWeight = FontWeight.SemiBold)
                    if (recordedSteps.isEmpty()) Text("No steps yet. Go to Chat and send a harmless request to add step 1.", color = MutedText, fontSize = 12.sp)
                    recordedSteps.forEachIndexed { index, step -> Text("${index + 1}. $step", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp) }
                    Button(onClick = { vm.stopMacroRecording(save = true) }, modifier = Modifier.fillMaxWidth()) { Text("Stop and save macro") }
                    OutlinedButton(onClick = { vm.stopMacroRecording(save = false) }, modifier = Modifier.fillMaxWidth()) { Text("Stop and discard") }
                } else {
                    OutlinedTextField(macroName, { macroName = it }, Modifier.fillMaxWidth(), label = { Text("Name this recording") }, singleLine = true)
                    Button(onClick = { vm.startMacroRecording(macroName); macroName = "" }, enabled = macroName.isNotBlank() && !working, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Record macro") }
                }
            }
            FeatureCard("${if (editingMacroId == null) "Build" else "Edit"} a typed macro · step $macroWizardStep of 2") {
                Text("Choose an action type, then fill only the details it needs. Open, tap and type use Android Accessibility. Chat requests use the app's deterministic local router; a search may use the web only when you explicitly run that request. No LLM or cloud AI is used.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (macroWizardStep == 1) {
                    OutlinedTextField(wizardMacroName, { wizardMacroName = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Macro name") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { macroKindMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Step type · ${addKind.label}") }
                            DropdownMenu(expanded = macroKindMenu, onDismissRequest = { macroKindMenu = false }) {
                                MacroStepKind.values().forEach { kind -> DropdownMenuItem(text = { Text(kind.label) }, onClick = {
                                    macroKindName = kind.name
                                    if (kind == MacroStepKind.RUN_ANOTHER_MACRO) selectedNestedMacroId = macros.firstOrNull { it.id != editingMacroId }?.id
                                    macroKindMenu = false
                                }) }
                            }
                        }
                    }
                    if (addKind.needsValue) {
                        OutlinedTextField(
                            macroPayload,
                            { value -> macroPayload = if (addKind == MacroStepKind.WAIT) value.filter(Char::isDigit).take(3) else value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH) },
                            Modifier.fillMaxWidth(),
                            label = { Text(addKind.fieldLabel) }, placeholder = { Text(addKind.placeholder) },
                            singleLine = addKind == MacroStepKind.WAIT,
                            keyboardOptions = if (addKind == MacroStepKind.WAIT) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default
                        )
                    }
                    if (addKind.needsMacro) {
                        Box {
                            OutlinedButton(onClick = { nestedMacroMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = macros.any { it.id != editingMacroId }) {
                                Text(macros.firstOrNull { it.id == selectedNestedMacroId }?.name ?: "Choose a saved macro")
                            }
                            DropdownMenu(expanded = nestedMacroMenu, onDismissRequest = { nestedMacroMenu = false }) {
                                macros.filter { it.id != editingMacroId }.take(40).forEach { nested -> DropdownMenuItem(text = { Text(nested.name) }, onClick = { selectedNestedMacroId = nested.id; nestedMacroMenu = false }) }
                            }
                        }
                    }
                    Text("Step ${macroDrafts.size + 1} of up to ${TypedMacroSteps.MAX_STEPS}", color = MutedText, fontSize = 12.sp)
                    Button(onClick = { addMacroDraft() }, enabled = canAddMacroDraft, modifier = Modifier.fillMaxWidth()) { Text("Add typed step") }
                    macroDrafts.forEachIndexed { index, row ->
                        key(row.id) {
                            val kind = MacroStepKind.forStep(row.step)
                            var handleCoordinates by remember(row.id) { mutableStateOf<LayoutCoordinates?>(null) }
                            var dragY by remember(row.id) { mutableStateOf(0f) }
                            Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                                val top = coordinates.positionInRoot().y
                                val bounds = top to (top + coordinates.size.height)
                                if (macroStepBounds[row.id] != bounds) macroStepBounds = macroStepBounds + (row.id to bounds)
                            }) {
                                FeatureCard("Step ${index + 1} · ${kind.label}") {
                                    Text("Hold and drag to reorder · move buttons are also available", color = MutedText, fontSize = 11.sp, modifier = Modifier
                                        .onGloballyPositioned { handleCoordinates = it }
                                        .pointerInput(row.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { local -> dragY = handleCoordinates?.localToRoot(local)?.y ?: 0f },
                                                onDragEnd = {},
                                                onDragCancel = {},
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragY += amount.y
                                                    val targetId = latestMacroStepBounds.value.entries.firstOrNull { (_, bounds) -> dragY >= bounds.first && dragY <= bounds.second }?.key
                                                    val currentRows = latestMacroDrafts.value
                                                    val from = currentRows.indexOfFirst { it.id == row.id }
                                                    val to = targetId?.let { id -> currentRows.indexOfFirst { it.id == id } } ?: -1
                                                    if (from >= 0 && to >= 0 && from != to) macroDrafts = MacroStepEditorModel.move(currentRows, from, to)
                                                }
                                            )
                                        })
                                    Box {
                                        OutlinedButton(onClick = { macroKindMenuFor = index }, modifier = Modifier.fillMaxWidth()) { Text("Change type · ${kind.label}") }
                                        DropdownMenu(expanded = macroKindMenuFor == index, onDismissRequest = { macroKindMenuFor = null }) {
                                            MacroStepKind.values().forEach { replacement -> DropdownMenuItem(text = { Text(replacement.label) }, onClick = {
                                                val nestedId = if (replacement == MacroStepKind.RUN_ANOTHER_MACRO) macros.firstOrNull { it.id != editingMacroId }?.id else null
                                                macroDrafts = MacroStepEditorModel.update(macroDrafts, index, replacement, replacement.placeholder, nestedId)
                                                macroKindMenuFor = null
                                            }) }
                                        }
                                    }
                                    if (kind.needsValue) OutlinedTextField(
                                        MacroStepKind.inputFor(row.step),
                                        { value ->
                                            val clean = if (kind == MacroStepKind.WAIT) value.filter(Char::isDigit).take(3) else value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH)
                                            macroDrafts = MacroStepEditorModel.update(macroDrafts, index, kind, clean, MacroStepKind.nestedIdFor(row.step))
                                        }, Modifier.fillMaxWidth(), label = { Text(kind.fieldLabel) },
                                        singleLine = kind == MacroStepKind.WAIT,
                                        keyboardOptions = if (kind == MacroStepKind.WAIT) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default
                                    )
                                    if (kind.needsMacro) {
                                        var nestedMenu by rememberSaveable(row.id) { mutableStateOf(false) }
                                        val currentId = MacroStepKind.nestedIdFor(row.step)
                                        Box {
                                            OutlinedButton(onClick = { nestedMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = macros.any { it.id != editingMacroId }) {
                                                Text(macros.firstOrNull { it.id == currentId }?.name ?: "Choose a saved macro")
                                            }
                                            DropdownMenu(expanded = nestedMenu, onDismissRequest = { nestedMenu = false }) {
                                                macros.filter { it.id != editingMacroId }.take(40).forEach { nested -> DropdownMenuItem(text = { Text(nested.name) }, onClick = {
                                                    macroDrafts = MacroStepEditorModel.update(macroDrafts, index, MacroStepKind.RUN_ANOTHER_MACRO, "", nested.id)
                                                    nestedMenu = false
                                                }) }
                                            }
                                        }
                                    }
                                    if (!kind.needsValue && !kind.needsMacro) Text("No extra details needed.", color = MutedText, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { addMacroDraft(index - 1) }, enabled = canAddMacroDraft) { Text("Insert before") }
                                        TextButton(onClick = { addMacroDraft(index) }, enabled = canAddMacroDraft) { Text("Insert after") }
                                        TextButton(onClick = { macroDrafts = MacroStepEditorModel.duplicate(macroDrafts, index) }, enabled = macroDrafts.size < TypedMacroSteps.MAX_STEPS) { Text("Duplicate") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { macroDrafts = MacroStepEditorModel.move(macroDrafts, index, index - 1) }, enabled = index > 0) { Text("Move up") }
                                        TextButton(onClick = { macroDrafts = MacroStepEditorModel.move(macroDrafts, index, index + 1) }, enabled = index < macroDrafts.lastIndex) { Text("Move down") }
                                        TextButton(onClick = { macroDrafts = MacroStepEditorModel.remove(macroDrafts, index) }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }
                    Text("Estimated time: about ${MacroStepEditorModel.estimatedSeconds(macroDrafts)} seconds (screen actions vary).", color = MutedText, fontSize = 12.sp)
                    Button(onClick = { macroWizardStep = 2 }, enabled = wizardMacroName.isNotBlank() && !duplicateMacroName && validationError == null, modifier = Modifier.fillMaxWidth()) { Text("Review macro") }
                    if (wizardMacroName.isNotBlank() && duplicateMacroName) Text("Another saved macro already uses that name.", color = Color(0xFFFFC978), fontSize = 12.sp)
                    if (macroDrafts.isNotEmpty() && validationError != null) Text(validationError, color = Color(0xFFFFC978), fontSize = 12.sp)
                    if (editingMacroId != null) OutlinedButton(onClick = { resetMacroEditor() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel editing") }
                } else {
                    Text("Review these ${macroDrafts.size} steps before saving:", fontWeight = FontWeight.SemiBold)
                    macroDrafts.forEachIndexed { index, row -> Text("${index + 1}. ${MacroStepEditorModel.describe(row.step, macros)}", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp) }
                    Text("Estimated time: about ${MacroStepEditorModel.estimatedSeconds(macroDrafts)} seconds. This saves the steps only; it does not run them.", color = MutedText, fontSize = 12.sp)
                    if (duplicateMacroName) Text("Another saved macro already uses that name.", color = Color(0xFFFFC978), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { macroWizardStep = 1 }, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(onClick = {
                            if (duplicateMacroName || validationError != null) return@Button
                            val id = editingMacroId
                            if (id == null) vm.saveTypedMacro(wizardMacroName, macroDrafts)
                            else vm.updateTypedMacro(id, wizardMacroName, macroDrafts)
                            if (tutorialStage > 0) { vm.markMacroTutorialSeen(); tutorialStage = 3 }
                            resetMacroEditor()
                        }, enabled = !duplicateMacroName && validationError == null, modifier = Modifier.weight(1f)) { Text(if (editingMacroId == null) "Save macro" else "Save changes") }
                    }
                    if (editingMacroId != null) TextButton(onClick = { resetMacroEditor() }) { Text("Cancel editing") }
                }
            }
            macroFailure?.let { failure ->
                FeatureCard("${failure.macro.name} paused at step ${failure.stepIndex + 1} of ${failure.stepCount}") {
                    Text(failure.message, color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                    Text("Later steps were not run. Fix the cause, then retry this step or dismiss.", color = MutedText, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::retryMacroFailure, enabled = !working, modifier = Modifier.weight(1f)) { Text("Retry failed step") }
                        OutlinedButton(onClick = vm::clearMacroFailure, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                    }
                }
            }
            if (working) OutlinedButton(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop current run") }
            macros.forEach { macro -> MacroCard(
                macro, allMacros = macros, enabled = !working,
                onEdit = { editMacro(macro) },
                onRun = { macroToRun = macro }, onDelete = { macroToDelete = macro }
            ) }
        }
    }
    macroToRun?.let { macro ->
        val previewSteps = remember(macro.id, macro.steps, macros) {
            runCatching { TypedMacroSteps.decode(macro.steps) }.getOrDefault(emptyList())
        }
        AlertDialog(
            onDismissRequest = { macroToRun = null },
            title = { Text("Review before running: ${macro.name}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("These actions run in order after you confirm. Search requests can use the web. Stop the run from this screen if you change your mind.", color = MutedText, fontSize = 13.sp)
                    if (previewSteps.isEmpty()) Text("The saved steps are empty or unreadable. Edit or replace this macro before running it.", color = Color(0xFFFFC978), fontSize = 13.sp)
                    previewSteps.forEachIndexed { index, step -> Text("${index + 1}. ${MacroStepEditorModel.describe(step, macros)}", fontSize = 13.sp, lineHeight = 18.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { if (previewSteps.isNotEmpty()) vm.runMacro(macro); macroToRun = null }, enabled = previewSteps.isNotEmpty()) { Text("Run macro") } },
            dismissButton = { TextButton(onClick = { macroToRun = null }) { Text("Cancel") } }
        )
    }
    macroToDelete?.let { macro ->
        AlertDialog(
            onDismissRequest = { macroToDelete = null },
            title = { Text("Delete ${macro.name}?") },
            text = { Text("This removes the saved macro from this phone. It will not run unless you choose Run.") },
            confirmButton = { TextButton(onClick = { vm.deleteMacro(macro.id); macroToDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { macroToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RuleCard(rule: AutomationRuleEntity, onDelete: () -> Unit) {
    FeatureCard("When you say: ${rule.triggerPhrase}") {
        Text("Run: ${rule.actionText}", color = MutedText, fontSize = 13.sp)
        TextButton(onClick = onDelete) { Text("Delete rule") }
    }
}

@Composable
private fun MacroCard(macro: MacroEntity, allMacros: List<MacroEntity>, enabled: Boolean, onEdit: () -> Unit, onRun: () -> Unit, onDelete: () -> Unit) {
    val steps = remember(macro.id, macro.steps, allMacros) { runCatching { TypedMacroSteps.decode(macro.steps) }.getOrDefault(emptyList()) }
    FeatureCard(macro.name) {
        if (steps.isEmpty()) Text("Saved steps are unreadable. Keep this macro or edit it after checking the stored data.", color = Color(0xFFFFC978), fontSize = 12.sp)
        else steps.forEachIndexed { index, step -> Text("${index + 1}. ${MacroStepEditorModel.describe(step, allMacros)}", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
            Button(onClick = onRun, enabled = enabled && steps.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Run") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
fun NotificationsScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    val rows by vm.notificationHistory.collectAsStateWithLifecycle()
    val counts by vm.notificationCategoryCounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var allowlist by rememberSaveable(settings.notificationAllowlist) { mutableStateOf(settings.notificationAllowlist) }
    var blocklist by rememberSaveable(settings.notificationBlocklist) { mutableStateOf(settings.notificationBlocklist) }
    var replyTo by remember { mutableStateOf<NotificationEntity?>(null) }
    var replyText by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryMenu by remember { mutableStateOf<String?>(null) }
    var clearTarget by remember { mutableStateOf<String?>(null) }
    var listenerEnabled by remember { mutableStateOf(NotificationAccessState.enabled(context)) }
    var listenerConnected by remember { mutableStateOf(NotificationAccessState.connected) }
    val pipelineState by NotificationPipelineTelemetry.snapshot.collectAsStateWithLifecycle()
    var testingCapture by remember { mutableStateOf(false) }
    var captureTestResult by remember { mutableStateOf<String?>(null) }
    val listenerState = context.getSharedPreferences("notification_capture_state", Context.MODE_PRIVATE)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = NotificationAccessState.enabled(context)
                listenerConnected = NotificationAccessState.connected
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val packages = rows.map { it.packageName }.distinct().take(30)
    val filtered = rows.filter { row ->
        (selectedCategory == null || row.category == selectedCategory) &&
            (query.isBlank() || listOf(row.title, row.text, row.packageName, row.groupName).any { it.contains(query, true) })
    }
    FeatureScaffold("Notifications", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Saved history · searchable any time") {
                Text("Search and filter the saved history below, even when live capture is off. Records stay on this phone in the encrypted database. Turning capture off keeps old history.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
            }
            FeatureCard("Live capture · separate from saved history") {
                Text("Android Notification Access lets the app read new notifications; this switch is your separate choice to save them. You can leave capture off and still search old records.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live notification capture", fontWeight = FontWeight.Medium)
                        Text(if (settings.notificationLiveCaptureEnabled) "On · filtered notifications may be saved" else "Off · nothing new is saved", color = MutedText, fontSize = 12.sp)
                    }
                    Switch(settings.notificationLiveCaptureEnabled, { enabled ->
                        vm.setNotificationLiveCaptureEnabled(enabled)
                        if (enabled && !NotificationAccessState.enabled(context)) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                .onFailure { vm.setToast("Could not open Notification Access settings: ${it.message ?: it.javaClass.simpleName}") }
                        }
                    })
                }
                Text("Android access: ${if (!listenerEnabled) "off" else if (listenerConnected) "on and connected" else "on, but disconnected"}", color = if (listenerConnected) MaterialTheme.colorScheme.secondary else MutedText, fontSize = 13.sp)
                if (!listenerEnabled) OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        .onFailure { vm.setToast("Could not open Notification Access settings: ${it.message ?: it.javaClass.simpleName}") }
                }, modifier = Modifier.fillMaxWidth()) { Text("Open Android Notification Access") }
                else if (!listenerConnected) OutlinedButton(onClick = {
                    val requested = NotificationAccessState.repair(context)
                    vm.setToast(if (requested) "Reconnection requested. MIUI may take a few seconds; check this page again." else "Reconnection could not be requested. Recheck Android Notification Access.")
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Fix listener connection") }
                Text("Last saved: ${listenerState.getLong("last_saved_at", 0L).takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "nothing saved yet"}", color = MutedText, fontSize = 12.sp)
                if (listenerState.getLong("listener_check_at", 0L) > 0) {
                    Text("Background check: ${listenerState.getString("listener_check_result", "unknown")} · WorkManager may run later than planned.", color = MutedText, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MIUI listener keep-alive", fontWeight = FontWeight.Medium)
                        Text("Optional. Shows a persistent Stop notification and asks Android to check again about every 6 hours. It cannot override MIUI battery rules.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Switch(settings.notificationKeepAliveEnabled, { vm.setNotificationKeepAliveEnabled(it) })
                }
                Text("On Redmi/MIUI, also allow Background autostart, choose No restrictions for battery use, and lock the app in Recents if your version offers that option. The keep-alive can be stopped here or from its notification.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
            }
            FeatureCard("Why is nothing saved?") {
                Text("These counts start when the app process starts. They show callback decisions, not notification text.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                Text("Received ${pipelineState.received} · Saved ${pipelineState.saved} · Removed ${pipelineState.removed} · Android access ${if (!listenerEnabled) "off" else if (listenerConnected) "connected" else "disconnected"} · Live capture ${if (settings.notificationLiveCaptureEnabled) "on" else "off"}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                val skipCounts = NotificationDecisionReason.values()
                    .filter { it != NotificationDecisionReason.SAVED }
                    .joinToString(" · ") { "${it.code} ${pipelineState.skippedByReason[it.code] ?: 0}" }
                Text("Skipped: $skipCounts", color = MutedText, fontSize = 11.sp, lineHeight = 16.sp)
                val savedAt = NotificationAccessState.lastSavedAt(context).takeIf { it > 0L }
                    ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "nothing saved yet"
                Text("Last saved: $savedAt", color = MutedText, fontSize = 12.sp)
                val callbackAt = pipelineState.lastCallbackAt.takeIf { it > 0L }
                    ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "not received in this process"
                Text("Last callback received: $callbackAt · logs include source, package, category, flags and reason; no message text", color = MutedText, fontSize = 11.sp, lineHeight = 16.sp)
                val connectedAt = NotificationAccessState.lastConnectedAt(context).takeIf { it > 0L }
                    ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "not recorded"
                val disconnectedAt = NotificationAccessState.lastDisconnectedAt(context).takeIf { it > 0L }
                    ?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "not recorded"
                Text("Listener times · connected: $connectedAt · disconnected: $disconnectedAt", color = MutedText, fontSize = 11.sp, lineHeight = 16.sp)
                if (pipelineState.recent.isNotEmpty()) {
                    Text("Last ${pipelineState.recent.size} decisions · app + reason only:", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    pipelineState.recent.forEach { decision ->
                        Text("${decision.packageName} · ${decision.reason}", color = MutedText, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                } else Text("No notification callbacks have been processed since this app started.", color = MutedText, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    if (!NotificationAccessState.enabled(context)) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            .onFailure { vm.setToast("Could not open Notification Access settings: ${it.message ?: it.javaClass.simpleName}") }
                    } else {
                        val requested = NotificationAccessState.repair(context)
                        vm.setToast(if (requested) "Listener repair requested. Wait a few seconds, then check this page again." else "Listener repair could not be requested. Recheck Android Notification Access.")
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Fix listener connection") }
                Text("Test capture posts a short app-owned notification, waits for the real listener and save decision, then removes the temporary test row. It requires Live capture to be on.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testingCapture = true
                            captureTestResult = "Waiting up to 10 seconds for the listener…"
                            val result = NotificationTestCapture.postAndWait(context)
                            captureTestResult = when {
                                result.decision?.saved == true && result.error == null -> "PASS — the notification reached the real listener, was saved, and its temporary history row was removed."
                                result.decision?.saved == true -> "SAVED — the listener saved the test row, but cleanup needs attention: ${result.error}"
                                result.decision != null -> "SKIPPED — ${result.decision.reason.code}. See the counters above."
                                else -> "NOT VERIFIED — ${result.error ?: "no decision received"}"
                            }
                            testingCapture = false
                        }
                    },
                    enabled = settings.notificationLiveCaptureEnabled && !testingCapture,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text(if (testingCapture) "Testing capture…" else "Test capture") }
                captureTestResult?.let { Text(it, color = if (it.startsWith("PASS")) MaterialTheme.colorScheme.secondary else MutedText, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            FeatureCard("Choose which apps can be saved") {
                Text("Default: all apps are allowed unless blocked. Choosing Allow on the first app creates an allow-only list. Blocked apps always win.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                if (packages.isEmpty()) Text("App choices appear here after notifications are saved.", color = MutedText, fontSize = 12.sp)
                packages.forEach { pkg ->
                    val blocked = blocklist.lines().any { it.trim().equals(pkg, true) }
                    val explicitlyAllowed = allowlist.lines().any { it.trim().equals(pkg, true) }
                    val appLabel = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("$appLabel  ·  $pkg", fontSize = 12.sp, color = MutedText)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            OutlinedButton(onClick = {
                                blocklist = blocklist.lines().filter { it.trim().isNotBlank() && !it.trim().equals(pkg, true) }.joinToString("\n")
                                allowlist = (allowlist.lines().filter { it.trim().isNotBlank() } + pkg).distinct().joinToString("\n")
                                vm.setNotificationPolicy(allowlist, blocklist)
                            }) { Text(if (!blocked && (allowlist.isBlank() || explicitlyAllowed)) "✓ Allowed" else "Allow") }
                            OutlinedButton(onClick = {
                                blocklist = (blocklist.lines().filter { it.trim().isNotBlank() } + pkg).distinct().joinToString("\n")
                                allowlist = allowlist.lines().filter { it.trim().isNotBlank() && !it.trim().equals(pkg, true) }.joinToString("\n")
                                vm.setNotificationPolicy(allowlist, blocklist)
                            }) { Text(if (blocked) "✓ Blocked" else "Block") }
                            OutlinedButton(onClick = {
                                blocklist = blocklist.lines().filter { it.trim().isNotBlank() && !it.trim().equals(pkg, true) }.joinToString("\n")
                                allowlist = allowlist.lines().filter { it.trim().isNotBlank() && !it.trim().equals(pkg, true) }.joinToString("\n")
                                vm.setNotificationPolicy(allowlist, blocklist)
                            }) { Text("Default") }
                        }
                        Box {
                            TextButton(onClick = { categoryMenu = pkg }) { Text("Change category · ${settings.notificationCategoryOverrides.lines().firstOrNull { it.substringBefore('=').equals(pkg, true) }?.substringAfter('=') ?: rows.firstOrNull { it.packageName == pkg }?.category ?: "Other"}") }
                            DropdownMenu(expanded = categoryMenu == pkg, onDismissRequest = { categoryMenu = null }) {
                                NotificationCategory.labels.forEach { label ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { vm.setNotificationCategoryOverride(pkg, label); categoryMenu = null })
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(allowlist, { allowlist = it }, Modifier.fillMaxWidth(), label = { Text("Allowlist · one package name per line") }, minLines = 2)
                OutlinedTextField(blocklist, { blocklist = it }, Modifier.fillMaxWidth(), label = { Text("Blocklist · one package name per line") }, minLines = 2)
                Button(onClick = { vm.setNotificationPolicy(allowlist, blocklist) }, modifier = Modifier.fillMaxWidth()) { Text("Save filters") }
                Text("Keep saved notifications for ${settings.notificationRetentionDays} days", color = MutedText, fontSize = 13.sp)
                Slider(settings.notificationRetentionDays.toFloat(), { value -> vm.setting { setNotificationRetentionDays(value.toInt()) } }, valueRange = 1f..365f)
            }
            FeatureCard("Find saved notifications") {
                Text("Category totals cover all retained records. This list shows the newest 500 records. Calls show caller only; SMS previews show sender only; chat previews show sender and message text.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search app, sender, or text") }, singleLine = true)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { selectedCategory = null }) { Text(if (selectedCategory == null) "✓ All" else "All") }
                    NotificationCategory.labels.forEach { label ->
                        val count = counts.firstOrNull { it.category == label }?.count ?: 0
                        OutlinedButton(onClick = { selectedCategory = if (selectedCategory == label) null else label }) {
                            Text("${if (selectedCategory == label) "✓ " else ""}$label · $count")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.markAllNotificationsRead() }, modifier = Modifier.weight(1f)) { Text("Mark all read") }
                    OutlinedButton(onClick = { clearTarget = selectedCategory ?: "__ALL__" }, modifier = Modifier.weight(1f)) { Text(if (selectedCategory == null) "Clear all" else "Clear category") }
                }
            }
            Text(if (settings.notificationLiveCaptureEnabled) "Recent saved notifications" else "Saved history · live capture is off", fontWeight = FontWeight.SemiBold)
            if (filtered.isEmpty()) Text("No saved notifications match. New items appear only when Live capture is on and Android Notification Access is connected.", color = MutedText)
            filtered.forEach { row ->
                val appLabel = remember(row.packageName) {
                    PackageLabelResolver.label(context, row.packageName)
                }
                val appIcon = remember(row.packageName) {
                    PackageLabelResolver.icon(context, row.packageName)?.toBitmap(48, 48)?.asImageBitmap()
                }
                NotificationItemCard(
                    row = row,
                    appLabel = appLabel,
                    appIcon = appIcon,
                    canReply = NotificationReplyRegistry.canReply(row.packageName, row.postedAt),
                    onOpen = {
                        runCatching { context.packageManager.getLaunchIntentForPackage(row.packageName) }
                            .onSuccess { launch ->
                                if (launch == null) vm.setToast("Android did not provide an Open app shortcut for $appLabel.")
                                else runCatching { context.startActivity(launch) }.onFailure { vm.setToast("Could not open $appLabel: ${it.message ?: it.javaClass.simpleName}") }
                            }
                            .onFailure { CrashLog.record("NOTIFICATIONS", "launch intent lookup failed for ${row.packageName}", it); vm.setToast("Could not find an Open app shortcut for $appLabel.") }
                    },
                    onReply = { replyTo = row; replyText = "" },
                    onReadAloud = { spokenText ->
                        scope.launch {
                            runCatching { VoiceSpeechOutput.speakOnce(context, spokenText) }
                                .onFailure { CrashLog.record("NOTIFICATIONS", "read aloud action failed", it); vm.setToast("Could not read that notification aloud.") }
                        }
                    },
                    onCopy = { copiedText ->
                        runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                ?: throw IllegalStateException("Clipboard service unavailable")
                            clipboard.setPrimaryClip(ClipData.newPlainText("Notification", copiedText))
                        }.onSuccess { vm.setToast("Notification text copied.") }
                            .onFailure { CrashLog.record("NOTIFICATIONS", "notification copy action failed", it); vm.setToast("Could not copy that notification.") }
                    },
                    onMarkRead = { vm.markNotificationRead(row.id) },
                    onMoveCategory = { label -> vm.setNotificationCategoryOverride(row.packageName, label) }
                )
            }
        }
    }
    replyTo?.let { row ->
        AlertDialog(
            onDismissRequest = { replyTo = null },
            title = { Text("Reply to ${row.title.ifBlank { row.packageName }}") },
            text = { OutlinedTextField(replyText, { replyText = it }, Modifier.fillMaxWidth(), label = { Text("Reply text") }, maxLines = 4) },
            confirmButton = { TextButton(onClick = {
                val sent = NotificationReplyRegistry.reply(context, row.packageName, row.postedAt, replyText)
                vm.setToast(if (sent) "Reply sent via the notification's active reply action." else "That notification's reply action expired or is unavailable.")
                replyTo = null
            }) { Text("Send reply") } },
            dismissButton = { TextButton(onClick = { replyTo = null }) { Text("Cancel") } }
        )
    }
    clearTarget?.let { target ->
        val all = target == "__ALL__"
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text(if (all) "Clear all saved notifications?" else "Clear $target notifications?") },
            text = { Text(if (all) "This permanently deletes the saved notification history on this phone. It does not change Android's current notifications or the live-capture setting." else "This permanently deletes saved notifications in $target only. It does not change Android's current notifications.") },
            confirmButton = { TextButton(onClick = { if (all) vm.clearNotificationHistory() else vm.clearNotificationCategory(target); clearTarget = null }) { Text("Delete saved history") } },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NotificationItemCard(
    row: NotificationEntity,
    appLabel: String,
    appIcon: androidx.compose.ui.graphics.ImageBitmap?,
    canReply: Boolean,
    onOpen: () -> Unit,
    onReply: () -> Unit,
    onReadAloud: (String) -> Unit,
    onCopy: (String) -> Unit,
    onMarkRead: () -> Unit,
    onMoveCategory: (String) -> Unit
) {
    var showText by rememberSaveable(row.id) { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    val sender = row.title.ifBlank { if (row.category == "Calls") "Caller not identified" else "Sender not identified" }
    val privateCategory = row.category == "Calls" || row.category == "Messages/SMS" || row.category == "Messages (SMS)"
    val safePreview = when (row.category) {
        "Calls" -> if (showText && row.text.isNotBlank()) "$sender: ${row.text}" else NotificationPrivacyFormatter.line(row.category, sender, row.text)
        "Messages/SMS", "Messages (SMS)" -> NotificationPrivacyFormatter.line(row.category, sender, row.text, includeMessageBody = showText)
        else -> NotificationPrivacyFormatter.line(row.category, sender, row.text)
    }.ifBlank { "(No readable text)" }
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (appIcon != null) Image(appIcon, contentDescription = "$appLabel app icon", modifier = Modifier.size(38.dp))
                else androidx.compose.material3.Icon(Icons.Filled.Notifications, contentDescription = "$appLabel notification", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp).padding(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(sender, color = MutedText, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (!row.isRead) Text("NEW", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            if (row.groupName.isNotBlank()) Text("Chat · ${row.groupName}", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
            Text(safePreview, color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
            Text("${row.category} · ${DateUtils.getRelativeTimeSpanString(row.postedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()} · ${if (row.isRead) "read" else "unread"}", color = MutedText, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open app") }
                if (canReply) OutlinedButton(onClick = onReply, modifier = Modifier.weight(1f)) { Text("Reply") }
                OutlinedButton(onClick = { onReadAloud(safePreview) }, modifier = Modifier.weight(1f)) { Text("Read aloud") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onCopy(safePreview) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                if (!row.isRead) OutlinedButton(onClick = onMarkRead, modifier = Modifier.weight(1f)) { Text("Mark read") }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { categoryExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Move category") }
                    DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        NotificationCategory.labels.forEach { label ->
                            DropdownMenuItem(
                                text = { Text(if (label == row.category) "✓ $label" else label) },
                                onClick = { onMoveCategory(label); categoryExpanded = false }
                            )
                        }
                    }
                }
            }
            if (privateCategory && row.text.isNotBlank()) {
                TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide text" else "Show text") }
            }
        }
    }
}

@Composable
fun ClipboardScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    val rows by vm.clipboardHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    FeatureScaffold("Clipboard history", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Foreground-only capture") {
                Text("When enabled, text copied while this app is open is stored in the encrypted local database. This can include sensitive text, so leave it off unless you want this history. Nothing is captured while the app is closed.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (settings.clipboardHistoryEnabled) "Enabled" else "Off", Modifier.weight(1f))
                    Switch(settings.clipboardHistoryEnabled, { enabled -> vm.setting { setClipboardHistoryEnabled(enabled) } })
                }
                OutlinedButton(onClick = { vm.clearClipboardHistory() }, modifier = Modifier.fillMaxWidth()) { Text("Clear history") }
            }
            if (rows.isEmpty()) Text("Clipboard history is empty.", color = MutedText)
            rows.forEach { entry ->
                FeatureCard(DateFormat.getDateTimeInstance().format(Date(entry.copiedAt))) {
                    Text(entry.text, color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                    TextButton(onClick = {
                        runCatching {
                            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                ?: throw IllegalStateException("Clipboard service unavailable")
                            manager.setPrimaryClip(ClipData.newPlainText("Clipboard history", entry.text))
                        }.onSuccess { vm.setToast("Copied text back to clipboard.") }.onFailure { vm.setToast("Could not copy: ${it.message ?: it.javaClass.simpleName}") }
                    }) { Text("Copy") }
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit, section: String = "all") {
    val context = LocalContext.current
    val toast by vm.toast.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var showBackupPass by rememberSaveable { mutableStateOf(false) }
    var restorePass by remember { mutableStateOf("") }
    var showRestorePass by rememberSaveable { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupStep by rememberSaveable { mutableStateOf(1) }
    var backupPreview by remember { mutableStateOf<com.cue.offlinebrain.data.EncryptedBackupPreview?>(null) }
    var backupPreviewError by remember { mutableStateOf<String?>(null) }
    var backupShareUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var savedBackupName by rememberSaveable { mutableStateOf("") }
    var lastBackupAt by rememberSaveable { mutableStateOf(context.getSharedPreferences("backup_status", Context.MODE_PRIVATE).getLong("last_portable_backup_at", 0L)) }
    var lastBackupBytes by rememberSaveable { mutableStateOf(context.getSharedPreferences("backup_status", Context.MODE_PRIVATE).getLong("last_portable_backup_bytes", 0L)) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var wipeText by rememberSaveable { mutableStateOf("") }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var existingPin by rememberSaveable { mutableStateOf("") }
    var removePin by rememberSaveable { mutableStateOf("") }
    var currentPin by rememberSaveable { mutableStateOf("") }
    var replacementPin by rememberSaveable { mutableStateOf("") }
    var replacementConfirm by rememberSaveable { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var showLockDelayMenu by remember { mutableStateOf(false) }
    val securityWorking by vm.securityWorking.collectAsStateWithLifecycle()
    val hasSavedPin = remember(settings.appLockEnabled) { PinLockStore.hasPin(context) || settings.appLockEnabled }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            vm.setToast("Saving the encrypted backup to the location you chose…")
            vm.exportEncryptedBackup(uri, backupPass) { success, size ->
                if (success) {
                    backupShareUri = uri
                    savedBackupName = try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column).orEmpty() else ""
                        }.orEmpty().ifBlank { "the file you chose" }
                    } catch (error: Exception) {
                        CrashLog.record("BACKUP", "saved backup display name could not be read", error)
                        "the file you chose"
                    }
                    lastBackupAt = System.currentTimeMillis()
                    lastBackupBytes = size ?: 0L
                    backupStep = 3
                    backupPass = ""; backupConfirm = ""
                }
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selectedRestoreUri = uri; backupPreview = null; backupPreviewError = null; restorePass = "" }
    }
    FeatureScaffold(if (section == "backup") "Backup and restore" else if (section == "security") "Privacy and security" else "Privacy & backups", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section != "backup") FeatureCard("Optional app lock") {
                Text("Use a 6–12 digit app PIN. PIN setup finishes saving before the app reports success. Android fingerprint or device-screen credentials are offered at the lock screen when supported. If you forget the PIN and your device credential is unavailable, the app cannot recover it.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (settings.appLockEnabled) {
                    Text("App lock is enabled. The PIN verifier stays saved on this device.", color = MaterialTheme.colorScheme.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hide app preview in Recents")
                            Text("Also blocks screenshots while this app is open.", color = MutedText, fontSize = 12.sp)
                        }
                        Switch(checked = settings.hideRecentsPreview, onCheckedChange = vm::setHideRecentsPreview)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lock after leaving the app", Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { showLockDelayMenu = true }) {
                                Text(when (settings.appLockTimeoutSeconds) { 0 -> "Immediately"; 30 -> "30 seconds"; 60 -> "1 minute"; 120 -> "2 minutes"; 300 -> "5 minutes"; 900 -> "15 minutes"; else -> "2 minutes" })
                            }
                            DropdownMenu(expanded = showLockDelayMenu, onDismissRequest = { showLockDelayMenu = false }) {
                                listOf(0 to "Immediately", 30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes", 900 to "15 minutes").forEach { (seconds, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { vm.setAppLockTimeoutSeconds(seconds); showLockDelayMenu = false })
                                }
                            }
                        }
                    }
                    Text("A short visit to Recents or another app does not lock the screen. The timer starts when this app actually leaves the foreground; a cold start always asks for your PIN.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                    var showDisable by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { showDisable = true; removePin = "" }, modifier = Modifier.fillMaxWidth()) { Text("Remove app lock") }
                    if (showDisable) AlertDialog(
                        onDismissRequest = { if (!securityWorking) showDisable = false },
                        title = { Text("Remove app lock?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Enter your current PIN to turn the lock off. The saved PIN stays on this device, so you can turn the lock back on without creating another one.")
                                OutlinedTextField(removePin, { removePin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Current PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = !securityWorking && removePin.length in 6..12, onClick = {
                                vm.disableAppLock(removePin) { disabled ->
                                    if (disabled) { showDisable = false; removePin = ""; pinError = null }
                                    else pinError = "The PIN did not match or checks are temporarily paused. The lock is still on."
                                }
                            }) { Text(if (securityWorking) "Checking…" else "Remove lock") }
                        },
                        dismissButton = { TextButton(onClick = { showDisable = false; removePin = "" }) { Text("Cancel") } }
                    )
                } else if (hasSavedPin) {
                    Text("A PIN is already saved. Enter it to enable the app lock; you do not need to create another PIN.", color = MutedText, fontSize = 13.sp)
                    OutlinedTextField(existingPin, { existingPin = it.filter(Char::isDigit).take(12); pinError = null }, Modifier.fillMaxWidth(), label = { Text("Saved app PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = {
                        vm.enableAppLockWithExistingPin(existingPin) { verified ->
                            if (verified) { existingPin = ""; pinError = null }
                            else pinError = "That PIN did not match, or attempts are temporarily paused. Wait briefly or use your device screen lock."
                        }
                    }, enabled = !securityWorking && existingPin.length in 6..12, modifier = Modifier.fillMaxWidth()) { Text(if (securityWorking) "Checking PIN…" else "Enable app lock") }
                } else {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Confirm PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = { vm.saveAppPin(pin, confirmPin); pin = ""; confirmPin = "" }, enabled = !securityWorking, modifier = Modifier.fillMaxWidth()) { Text(if (securityWorking) "Saving PIN securely…" else "Set PIN and enable app lock") }
                }
                if (hasSavedPin) {
                    Text("Change saved PIN", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(currentPin, { currentPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Current PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(replacementPin, { replacementPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(replacementConfirm, { replacementConfirm = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Confirm new PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = { vm.changeAppPin(currentPin, replacementPin, replacementConfirm); currentPin = ""; replacementPin = ""; replacementConfirm = "" }, enabled = !securityWorking, modifier = Modifier.fillMaxWidth()) { Text(if (securityWorking) "Saving PIN securely…" else "Change PIN") }
                }
                pinError?.let { Text(it, color = Color(0xFFFF8C94), fontSize = 13.sp) }
            }
            if (section == "all") FeatureCard("Background refresh") {
                Text("When enabled, weather, headlines and an optional saved custom API are refreshed about every 12 hours. WorkManager requires network and a battery that is not low; Android may defer the run. Custom API headers are sent only to the URL you saved.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.backgroundRefreshEnabled) "Enabled" else "Off", Modifier.weight(1f)); Switch(settings.backgroundRefreshEnabled, { vm.setBackgroundRefreshEnabled(it) }) }
            }
            if (section != "security") FeatureCard("Daily encrypted backup on this device") {
                Text("Off by default. When enabled, the app keeps an encrypted backup in private storage on this phone. It does not upload the file. Use the portable backup below to move it to another device.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.autoBackupEnabled) "Enabled" else "Off", Modifier.weight(1f)); Switch(settings.autoBackupEnabled, { vm.setAutoBackupEnabled(it) }) }
            }
            if (section != "security") FeatureCard("How backup works") {
                Text("A portable backup is locked with your passphrase and saved only to the folder you choose. It is not uploaded. You need the same passphrase to restore it; if you forget it, nobody can recover the file. The optional daily backup stays on this phone and is off by default.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (section != "security") FeatureCard("Portable encrypted backup") {
                Text("The file contains chats, alarms, notes, saved notifications, rules, macros, clipboard history, learned phrases and portable settings. Saved custom API headers are included only inside the encrypted file. Your app PIN and Android permissions stay on this phone. The passphrase is never saved by the app.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (lastBackupAt > 0L) {
                    val sizeLabel = if (lastBackupBytes < 1024L) "$lastBackupBytes bytes" else "${lastBackupBytes / 1024L} KB"
                    Text("Last portable backup: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastBackupAt))} · $sizeLabel", color = MutedText, fontSize = 12.sp)
                } else Text("Last portable backup: none recorded on this phone yet.", color = MutedText, fontSize = 12.sp)

                if (backupStep == 1) {
                    Text("Create a backup", fontWeight = FontWeight.SemiBold)
                    Text("Choose a long passphrase (at least 10 characters). You will need the same passphrase to restore the file.", color = MutedText, fontSize = 12.sp)
                    Button(onClick = { backupStep = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Start backup") }
                } else if (backupStep == 2) {
                    Text("Step 1 of 2 · Set a passphrase", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(backupPass, { backupPass = it }, Modifier.fillMaxWidth(), label = { Text("Backup passphrase") }, visualTransformation = if (showBackupPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(backupConfirm, { backupConfirm = it }, Modifier.fillMaxWidth(), label = { Text("Type it again") }, visualTransformation = if (showBackupPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    TextButton(onClick = { showBackupPass = !showBackupPass }) { Text(if (showBackupPass) "Hide passphrase" else "Show passphrase") }
                    Text(when {
                        backupPass.length < 10 -> "Use at least 10 characters. A longer, memorable phrase is safer."
                        backupPass != backupConfirm -> "The two passphrases do not match yet."
                        else -> "Passphrase confirmed. It will not be stored by the app."
                    }, color = MutedText, fontSize = 12.sp)
                    Button(onClick = { createBackup.launch("cue-backup.cueb") }, enabled = backupPass.length >= 10 && backupPass == backupConfirm, modifier = Modifier.fillMaxWidth()) { Text("Choose where to save") }
                    TextButton(onClick = { backupStep = 1; backupPass = ""; backupConfirm = "" }) { Text("Cancel") }
                } else {
                    Text("Backup saved", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                    Text("Saved as $savedBackupName. The file is encrypted. Keep your passphrase somewhere safe; Cue cannot recover it.", color = MutedText, fontSize = 12.sp)
                    if (backupShareUri != null) Button(onClick = {
                        try {
                            val uri = backupShareUri ?: return@Button
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/octet-stream")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            intent.clipData = android.content.ClipData.newUri(context.contentResolver, "Encrypted backup", uri)
                            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(intent, "Share encrypted backup"))
                        } catch (error: Exception) {
                            CrashLog.record("BACKUP", "backup share chooser failed", error)
                            vm.setToast("Android could not open the share menu.")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Share backup file") }
                    TextButton(onClick = { backupStep = 1 }) { Text("Create another backup") }
                }

                androidx.compose.material3.HorizontalDivider()
                Text("Restore a backup", fontWeight = FontWeight.SemiBold)
                Text("Choose a file and enter its passphrase. The preview checks the encrypted file and shows its contents before you replace anything.", color = MutedText, fontSize = 12.sp)
                OutlinedButton(onClick = { openBackup.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (selectedRestoreUri == null) "Choose backup file" else "Choose a different file") }
                selectedRestoreUri?.let { uri ->
                    Text("File selected. It has not been restored.", color = MutedText, fontSize = 12.sp)
                    OutlinedTextField(restorePass, { restorePass = it; backupPreview = null; backupPreviewError = null }, Modifier.fillMaxWidth(), label = { Text("Backup passphrase") }, visualTransformation = if (showRestorePass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    TextButton(onClick = { showRestorePass = !showRestorePass }) { Text(if (showRestorePass) "Hide passphrase" else "Show passphrase") }
                    Button(onClick = { backupPreview = null; backupPreviewError = "Checking the encrypted file…"; vm.previewEncryptedBackup(uri, restorePass) { result, error -> backupPreview = result; backupPreviewError = error } }, enabled = restorePass.length >= 10 && backupPreviewError != "Checking the encrypted file…", modifier = Modifier.fillMaxWidth()) { Text(if (backupPreviewError == "Checking the encrypted file…") "Checking…" else "Check passphrase and preview") }
                    backupPreviewError?.let { Text(it, color = if (backupPreview == null && it.startsWith("This backup could not")) Color(0xFFFF8C94) else MutedText, fontSize = 12.sp) }
                    backupPreview?.let { preview ->
                        Text("Contents in this file", fontWeight = FontWeight.Medium)
                        Text("${preview.messages} chats · ${preview.alarms} alarms · ${preview.notes} notes · ${preview.notifications} saved notifications · ${preview.rules} rules · ${preview.macros} macros · ${preview.clipboardEntries} clipboard items · ${preview.phrases} learned phrases · ${preview.settings} settings", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                        Text("Records are replaced in a Room database transaction. Portable settings are restored separately afterward.", color = MutedText, fontSize = 12.sp)
                        OutlinedButton(onClick = { showRestoreConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Review replacement") }
                    }
                }
            }
            if (section != "backup") FeatureCard("Delete local user data") {
                Text("This removes chats, alarms, notes, saved notification and clipboard text, rules, macros, local caches, settings and private automatic backups. Built-in FAQ data and the encrypted database key remain so the app can open safely. Export first if you may need this data.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedButton(onClick = { wipeText = ""; showWipeConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Wipe local user data") }
            }
            toast?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
        }
    }
    if (showRestoreConfirm) AlertDialog(
        onDismissRequest = { showRestoreConfirm = false },
        title = { Text("Replace this phone's records?") },
        text = {
            val preview = backupPreview
            Text(if (preview == null) "The verified preview is no longer available. Cancel and check the file again."
            else "This encrypted file contains ${preview.messages} chats, ${preview.alarms} alarms, ${preview.notes} notes, ${preview.notifications} saved notifications, ${preview.rules} rules, ${preview.macros} macros, ${preview.clipboardEntries} clipboard items and ${preview.phrases} learned phrases. Replacing records cannot be undone. Database records are replaced in one transaction; portable settings are restored afterward.")
        },
        confirmButton = { TextButton(enabled = backupPreview != null && selectedRestoreUri != null, onClick = {
            val uri = selectedRestoreUri
            if (uri != null) vm.restoreEncryptedBackup(uri, restorePass)
            showRestoreConfirm = false; selectedRestoreUri = null; backupPreview = null; backupPreviewError = null; restorePass = ""
        }) { Text("Restore and replace") } },
        dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
    )
    if (showWipeConfirm) AlertDialog(
        onDismissRequest = { showWipeConfirm = false },
        title = { Text("Wipe local user data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This permanently deletes local user records and settings on this device. Type WIPE exactly to enable the final button.")
                OutlinedTextField(wipeText, { wipeText = it.take(8) }, label = { Text("Type WIPE") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = wipeText == "WIPE", onClick = { vm.wipeAllUserData(); showWipeConfirm = false; wipeText = "" }) { Text("Wipe data") } },
        dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } }
    )
}

@Composable
fun AppLockScreen(vm: BrainViewModel, onUnlocked: () -> Unit, onBiometric: (() -> Unit) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Offline Brain is locked", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12); error = null }, Modifier.fillMaxWidth(), label = { Text("App PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
        error?.let { Text(it, color = Color(0xFFFF8C94), modifier = Modifier.padding(top = 6.dp)) }
        Button(onClick = {
            checking = true
            vm.verifyAppPin(pin) { verified ->
                checking = false
                if (verified) onUnlocked() else { error = "That PIN did not match, or attempts are temporarily paused. Wait briefly or use your device screen lock."; pin = "" }
            }
        }, enabled = !checking && pin.length in 6..12, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (checking) "Checking…" else "Unlock") }
        OutlinedButton(onClick = { onBiometric { onUnlocked() } }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text("Use fingerprint or device screen lock") }
        Text(if (checking) "Checking the saved PIN securely on this device…" else "PIN verification stays on this device. Five wrong tries pause PIN checks for 30 seconds.", color = MutedText, fontSize = 12.sp)
    }
}
