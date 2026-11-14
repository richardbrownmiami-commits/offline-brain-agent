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

@Composable
fun AutomationScreen(feature: AutomationViewModel, onBack: () -> Unit) {
    val vm = feature.coordinator
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
