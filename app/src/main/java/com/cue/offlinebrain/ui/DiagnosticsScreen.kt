package com.cue.offlinebrain.ui

import com.cue.offlinebrain.AppBackground
import com.cue.offlinebrain.SurfaceDark
import com.cue.offlinebrain.Muted
import com.cue.offlinebrain.Teal
import com.cue.offlinebrain.SettingsCard

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.AppLockStateStore
import com.cue.offlinebrain.data.AppLockLifecycle
import com.cue.offlinebrain.data.AlarmEntity
import com.cue.offlinebrain.data.MessageEntity
import com.cue.offlinebrain.data.NoteEntity
import com.cue.offlinebrain.data.ClipboardEntryEntity
import com.cue.offlinebrain.data.ClipboardCapturePolicy
import com.cue.offlinebrain.summary.ResumeEvidenceMarkup
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.voice.SpeechDiagnostics
import com.cue.offlinebrain.voice.VoiceEngineMode
import com.cue.offlinebrain.device.DeviceStatusProvider
import com.cue.offlinebrain.diagnostics.DiagnosticResult
import com.cue.offlinebrain.ui.BrainViewModel
import com.cue.offlinebrain.ui.FirstRunSelfCheckScreen
import com.cue.offlinebrain.ui.AppLockScreen
import com.cue.offlinebrain.ui.AutomationScreen
import com.cue.offlinebrain.ui.ClipboardScreen
import com.cue.offlinebrain.ui.NotificationsScreen
import com.cue.offlinebrain.ui.PhraseTeachingScreen
import com.cue.offlinebrain.ui.PrivacyScreen
import com.cue.offlinebrain.ui.SettingsHomeScreen
import com.cue.offlinebrain.ui.ToolsHomeScreen
import com.cue.offlinebrain.ui.AccessibilityOffBanner
import com.cue.offlinebrain.ui.FloatingButtonSetupScreen
import com.cue.offlinebrain.ui.AppearanceSettingsScreen
import com.cue.offlinebrain.ui.VoiceSettingsScreen
import com.cue.offlinebrain.ui.OnlineSettingsScreen
import com.cue.offlinebrain.ui.PermissionsSettingsScreen
import com.cue.offlinebrain.ui.AboutHelpScreen
import com.cue.offlinebrain.overlay.FloatingAssistantOverlayService
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreen(feature: DiagnosticsViewModel, onBack: () -> Unit) {
    val vm = feature.coordinator
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    val results by vm.diagnostics.collectAsStateWithLifecycle()
    val running by vm.diagnosticsRunning.collectAsStateWithLifecycle()
    val message by vm.toast.collectAsStateWithLifecycle()
    val voiceSettings by vm.settings.collectAsStateWithLifecycle()
    val speechSession by vm.speechSession.collectAsStateWithLifecycle()
    val micEvents by SpeechDiagnostics.eventFlow.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.runVoiceDiagnostic() else vm.setToast("Microphone permission was not granted. Text features still work.")
    }
    Scaffold(
        containerColor = AppBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tests run on this phone. The alarm test waits up to about 70 seconds. UNTESTED means this screen cannot safely check it by itself or a real phone interaction is needed; each card explains how to finish the check.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Button(onClick = { vm.runDiagnostics() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run all diagnostics")
            }
            Button(onClick = { vm.scheduleTestAlarmIn30Seconds() }, modifier = Modifier.fillMaxWidth()) { Text("Test alarm in 30 seconds") }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Microphone test", fontWeight = FontWeight.SemiBold)
                    Text("Tap Say hello, then speak “hello” once. The result is shown here only; it is not sent or saved as a chat message.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.runVoiceDiagnostic()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = !speechSession.listening && !speechSession.processing, modifier = Modifier.fillMaxWidth()) { Text("Say hello") }
                    Text("Engine used: ${speechSession.engine.ifBlank { voiceSettings.voiceEngineMode.name }}", color = Teal, fontSize = 12.sp)
                    Text("Status: ${speechSession.status}", color = Muted, fontSize = 12.sp)
                    Text("Error code: ${speechSession.errorCode?.toString() ?: "none"}", color = Muted, fontSize = 12.sp)
                    Text("Recognized text: ${speechSession.recognizedText.ifBlank { "Not recognized yet" }}", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("Last microphone events", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    if (micEvents.isEmpty()) Text("No microphone events yet.", color = Muted, fontSize = 11.sp)
                    micEvents.takeLast(5).forEach { event ->
                        Text("${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(event.timestampMillis))} · ${event.engine}${event.errorCode?.let { " · code $it" }.orEmpty()} · ${event.status}", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(Intent.createChooser(CrashLog.shareIntent(context), "Share diagnostic log")) }
                        .onFailure { vm.setToast("Sharing is unavailable. Use Copy log or View log instead: ${it.message ?: it.javaClass.simpleName}") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Share log") }
            androidx.compose.material3.OutlinedButton(onClick = {
                val clipboard = runCatching { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager }.getOrNull()
                if (clipboard == null) vm.setToast("Clipboard service is unavailable. Use View log instead.")
                else {
                    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Offline Brain diagnostics", CrashLog.readText(context))) }
                        .onSuccess { vm.setToast("Diagnostic log copied to clipboard.") }
                        .onFailure { vm.setToast("Could not copy the log: ${it.message ?: it.javaClass.simpleName}") }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy log to clipboard") }
            androidx.compose.material3.OutlinedButton(onClick = {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    val version = "${info.versionName ?: "unknown"} (${info.longVersionCode})"
                    buildString {
                        appendLine("Offline Brain Agent · Full diagnostic report")
                        appendLine("Generated: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("MIUI/OEM incremental version: ${Build.VERSION.INCREMENTAL}")
                        appendLine("Build: ${Build.DISPLAY}")
                        appendLine("App: $version")
                        appendLine()
                        appendLine("Checks:")
                        if (results.isEmpty()) appendLine("No checks have run.")
                        results.forEach { appendLine("${it.status.name} · ${it.name}: ${it.reason}") }
                        appendLine()
                        appendLine("Local diagnostic log (chat contents and credentials are not recorded):")
                        append(CrashLog.readText(context))
                    }
                }.onSuccess { report ->
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard == null) vm.setToast("Clipboard service is unavailable.")
                    else runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Offline Brain full diagnostic report", report)) }
                        .onSuccess { vm.setToast("Full diagnostic report copied. Review it before sharing.") }
                        .onFailure { vm.setToast("Could not copy the report: ${it.message ?: it.javaClass.simpleName}") }
                }.onFailure { vm.setToast("Could not build the report: ${it.message ?: it.javaClass.simpleName}") }
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy full diagnostic report") }
            Text("The full report includes this phone's model and app version. Check it before sharing; copying does not send it anywhere.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            androidx.compose.material3.OutlinedButton(onClick = { logText = CrashLog.readText(context); showLog = true }, modifier = Modifier.fillMaxWidth()) { Text("View log") }
            message?.let { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF242538))) { Text(it, Modifier.padding(12.dp), fontSize = 13.sp) } }
            if (results.isEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Text("No checks have run yet. Start a test to see the result and reason for each item.", Modifier.padding(14.dp), color = Muted)
                }
            } else {
                results.forEach { result ->
                    val tint = when (result.status) {
                        DiagnosticResult.Status.PASS -> Teal
                        DiagnosticResult.Status.FAIL -> Color(0xFFFF8C94)
                        DiagnosticResult.Status.RUNNING -> Color(0xFFFFC978)
                        DiagnosticResult.Status.UNTESTED -> Muted
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp)) {
                            Text("${result.status.name}  ·  ${result.name}", color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(result.reason, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
            Text("Crash and system-event details are stored in a bounded local log. It excludes chat contents and credentials.", color = Muted, fontSize = 12.sp)
        }
    }
    if (showLog) AlertDialog(
        onDismissRequest = { showLog = false },
        title = { Text("Diagnostic log") },
        text = {
            Text(logText, Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(8.dp), fontSize = 12.sp, lineHeight = 17.sp)
        },
        confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } }
    )
}
