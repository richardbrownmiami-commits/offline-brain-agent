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
