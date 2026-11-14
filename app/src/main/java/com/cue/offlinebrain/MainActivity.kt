package com.cue.offlinebrain

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

private val AppBackground = Color(0xFF101118)
private val SurfaceDark = Color(0xFF1A1B25)
private val Muted = Color(0xFFAAA9B6)
private val Teal = Color(0xFF8FE3D8)

class MainActivity : FragmentActivity() {
    private val brainViewModel: BrainViewModel by viewModels()
    private var biometricPrompt: BiometricPrompt? = null
    private var activityResumed = false
    private var clipboardEnabled = false
    private var clipboardRegistered = false
    private var clipboardManager: ClipboardManager? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboardText() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as OfflineBrainApplication
            val restoreIssue by app.restoreRecoveryError.collectAsStateWithLifecycle()
            if (restoreIssue != null) RestoreRecoveryScreen(app, restoreIssue.orEmpty())
            else OfflineBrainApp(brainViewModel)
        }
        lifecycleScope.launch {
            try {
            val app = application as OfflineBrainApplication
            combine(app.settings.settings, app.restoreRecoveryError) { current, recoveryIssue -> current to recoveryIssue }.collect { (current, recoveryIssue) ->
                    clipboardEnabled = current.clipboardHistoryEnabled && recoveryIssue == null
                    if (current.hideRecentsPreview) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    updateClipboardListener()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { CrashLog.record("CLIPBOARD", "clipboard setting observer failed", error) }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        brainViewModel.refreshAccessibilityStatus()
        com.cue.offlinebrain.data.AppLockLifecycle.noteActivityResumed()
        updateClipboardListener()
    }

    override fun onPause() {
        com.cue.offlinebrain.data.AppLockLifecycle.noteActivityPaused()
        activityResumed = false
        updateClipboardListener()
        super.onPause()
    }

    private fun updateClipboardListener() {
        try {
            if (!activityResumed || !clipboardEnabled) {
                if (clipboardRegistered) clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
                clipboardRegistered = false
                return
            }
            val manager = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?: throw IllegalStateException("Clipboard service unavailable")
            clipboardManager = manager
            if (!clipboardRegistered) {
                manager.addPrimaryClipChangedListener(clipboardListener)
                clipboardRegistered = true
                CrashLog.record("CLIPBOARD", "foreground-only clipboard history listener enabled")
            }
        } catch (error: Exception) {
            clipboardRegistered = false
            CrashLog.record("CLIPBOARD", "clipboard listener could not be updated", error)
        }
    }

    private fun captureClipboardText() {
        if (!activityResumed || !clipboardEnabled) return
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = ClipboardCapturePolicy.normalize(clip.getItemAt(0).coerceToText(this)?.toString()) ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dao = (application as OfflineBrainApplication).database.dao()
                    if (dao.latestClipboardText() != text) dao.addClipboardEntry(ClipboardEntryEntity(text = text))
                    CrashLog.record("CLIPBOARD", "foreground clipboard text stored")
                } catch (error: Exception) { CrashLog.record("CLIPBOARD", "clipboard text could not be stored", error) }
            }
        } catch (error: Exception) { CrashLog.record("CLIPBOARD", "clipboard read failed", error) }
    }

    fun showBiometricUnlock(onSuccess: () -> Unit) {
        try {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricPrompt = null
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricPrompt = null
                    CrashLog.record("SECURITY", "biometric/device credential prompt ended with code=$errorCode")
                }
            })
            biometricPrompt = prompt
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Offline Brain")
                .setSubtitle("Use a fingerprint or your device screen lock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(info)
        } catch (error: Exception) {
            biometricPrompt = null
            CrashLog.record("SECURITY", "biometric prompt could not start", error)
            brainViewModel.setToast("Biometric unlock is unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}

@Composable
private fun RestoreRecoveryScreen(app: OfflineBrainApplication, issue: String) {
    var retrying by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Teal, background = AppBackground, surface = SurfaceDark)) {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Finishing a saved restore", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                Text(issue, Modifier.padding(top = 16.dp), color = Muted, textAlign = TextAlign.Center)
                Text("Your other app features are paused so this recovery copy cannot be overwritten.", Modifier.padding(top = 12.dp), color = Muted, textAlign = TextAlign.Center)
                Button(
                    onClick = {
                        if (!retrying) {
                            retrying = true
                            scope.launch {
                                try { app.retryRestoreRecovery() }
                                finally { retrying = false }
                            }
                        }
                    },
                    enabled = !retrying,
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    if (retrying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Try recovery again")
                }
            }
        }
    }
}

@Composable
private fun OfflineBrainApp(vm: BrainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val activityContext = LocalContext.current
    val color = Color(settings.accent)
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = color, secondary = Teal, background = AppBackground, surface = SurfaceDark,
            onPrimary = Color(0xFF171522), onBackground = Color(0xFFF2F0FB), onSurface = Color(0xFFF2F0FB)
        )
    ) {
        if (!settingsReady) {
            Surface(Modifier.fillMaxSize(), color = AppBackground) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text("Loading privacy settings…", Modifier.padding(top = 12.dp), color = Muted)
                }
            }
        } else {
            val processLockRequired by AppLockLifecycle.lockRequired.collectAsStateWithLifecycle()
            val lockEnabled = settings.appLockEnabled || AppLockStateStore.isEnabled(activityContext)
            val initialGateRequiresUnlock = remember { AppLockLifecycle.initializeFirstActivityGate(lockEnabled) }
            var unlocked by remember { mutableStateOf(!lockEnabled || !initialGateRequiresUnlock) }
            LaunchedEffect(lockEnabled, processLockRequired) {
                if (lockEnabled && processLockRequired) unlocked = false
                if (!lockEnabled) { unlocked = true; AppLockLifecycle.markUnlocked() }
            }
            if (lockEnabled && (processLockRequired || !unlocked)) {
                AppLockScreen(vm, onUnlocked = { AppLockLifecycle.markUnlocked(); unlocked = true }, onBiometric = { success ->
                    val activity = activityContext as? MainActivity
                    activity?.showBiometricUnlock(success) ?: vm.setToast("System biometric unlock is not available here.")
                })
            } else {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val current = entry?.destination?.route ?: "chat"
        LaunchedEffect(settings.selfCheckSeen, current) {
            if (settings.selfCheckSeen && current == "self-check") {
                nav.navigate("chat") { popUpTo("self-check") { inclusive = true }; launchSingleTop = true }
            }
        }
        Scaffold(
            containerColor = AppBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar(containerColor = SurfaceDark, modifier = Modifier.navigationBarsPadding().testTag("bottom_navigation")) {
                    NavigationBarItem(selected = current == "chat", onClick = { nav.navigate("chat") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Chat") })
                    NavigationBarItem(selected = current == "alarms" || current == "settings/alarms", onClick = { nav.navigate("alarms") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.AccessTime, null) }, label = { Text("Alarms") })
                    NavigationBarItem(selected = current == "notifications" || current == "settings/notifications", onClick = { nav.navigate("notifications") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.Notifications, null) }, label = { Text("Notifications") })
                    NavigationBarItem(selected = current in setOf("tools", "phrases", "automation", "floating", "notes", "clipboard", "settings/tools"), onClick = { nav.navigate("tools") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.TaskAlt, null) }, label = { Text("Tools") })
                    NavigationBarItem(selected = current == "settings" || current.startsWith("settings/") || current in setOf("diagnostics", "privacy"), onClick = { nav.navigate("settings") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            NavHost(navController = nav, startDestination = "self-check", modifier = Modifier.padding(padding)) {
                composable("self-check") {
                    FirstRunSelfCheckScreen(
                        vm = vm,
                        onTryInput = { vm.setting { markSelfCheckSeen() }; nav.navigate("chat") { popUpTo("self-check") { inclusive = true }; launchSingleTop = true } },
                        onPermissions = { nav.navigate("settings") },
                        onContinue = { vm.setting { markSelfCheckSeen() }; nav.navigate("chat") { popUpTo("self-check") { inclusive = true }; launchSingleTop = true } }
                    )
                }
                composable("chat") { ChatScreen(vm, settings) }
                composable("alarms") { AlarmsScreen(vm, settings) { nav.popBackStack() } }
                composable("notes") { NotesScreen(vm) { nav.popBackStack() } }
                composable("settings") { SettingsHomeScreen(onSelect = { nav.navigate("settings/$it") }, onBack = { nav.popBackStack() }) }
                composable("settings/{section}") { backStackEntry ->
                    val section = backStackEntry.arguments?.getString("section").orEmpty()
                    when (section) {
                        "appearance" -> AppearanceSettingsScreen(vm, settings) { nav.popBackStack() }
                        "voice" -> VoiceSettingsScreen(vm, settings) { nav.popBackStack() }
                        "alarms" -> AlarmsScreen(vm, settings) { nav.popBackStack() }
                        "notifications" -> NotificationsScreen(vm, settings) { nav.popBackStack() }
                        "tools" -> ToolsHomeScreen(vm, onSelect = { nav.navigate(it) }, onBack = { nav.popBackStack() })
                        "online" -> OnlineSettingsScreen(vm, settings) { nav.popBackStack() }
                        "privacy" -> PrivacyScreen(vm, settings, { nav.popBackStack() }, section = "security")
                        "backup" -> PrivacyScreen(vm, settings, { nav.popBackStack() }, section = "backup")
                        "permissions" -> PermissionsSettingsScreen(vm) { nav.popBackStack() }
                        "diagnostics" -> DiagnosticsScreen(vm) { nav.popBackStack() }
                        else -> AboutHelpScreen { nav.popBackStack() }
                    }
                }
                composable("tools") { ToolsHomeScreen(vm, onSelect = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
                composable("floating") { FloatingButtonSetupScreen(vm) { nav.popBackStack() } }
                composable("diagnostics") { DiagnosticsScreen(vm) { nav.popBackStack() } }
                composable("phrases") { PhraseTeachingScreen(vm, settings) { nav.popBackStack() } }
                composable("automation") { AutomationScreen(vm) { nav.popBackStack() } }
                composable("notifications") { NotificationsScreen(vm, settings) { nav.popBackStack() } }
                composable("clipboard") { ClipboardScreen(vm, settings) { nav.popBackStack() } }
                composable("privacy") { PrivacyScreen(vm, settings, { nav.popBackStack() }, section = "security") }
            }
        }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: BrainViewModel, settings: AppSettings) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val voiceStatus by vm.voiceStatus.collectAsStateWithLifecycle()
    val voiceProgress by vm.voiceProgress.collectAsStateWithLifecycle()
    val voiceRecording by vm.voiceRecording.collectAsStateWithLifecycle()
    val speechSession by vm.speechSession.collectAsStateWithLifecycle()
    val voiceTranscript by vm.voiceTranscript.collectAsStateWithLifecycle()
    val macroRecording by vm.macroRecording.collectAsStateWithLifecycle()
    val macroName by vm.recordingMacroName.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val hostView = LocalView.current
    var androidVoiceSeconds by rememberSaveable { mutableStateOf(12) }
    var pendingFlashCommand by rememberSaveable { mutableStateOf<String?>(null) }
    var showFlashPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPhoneCommand by rememberSaveable { mutableStateOf<String?>(null) }
    var showPhonePermissionDialog by rememberSaveable { mutableStateOf(false) }
    val flashlightPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val command = pendingFlashCommand
        pendingFlashCommand = null
        if (granted && command != null) vm.send(command)
        else if (command != null) notice = "Camera permission was not granted. The flashlight was not changed."
    }
    val phoneStatePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val command = pendingPhoneCommand
        pendingPhoneCommand = null
        if (granted && command != null) vm.send(command)
        else if (command != null) notice = "Phone permission was not granted. Cue did not read the carrier name."
    }
    fun submitChatCommand(command: String, speakReply: Boolean = false) {
        if (DeviceStatusProvider.needsCameraPermission(command) && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingFlashCommand = command
            showFlashPermissionDialog = true
        } else if (DeviceStatusProvider.needsPhoneStatePermission(command) && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            pendingPhoneCommand = command
            showPhonePermissionDialog = true
        } else vm.send(command, speakReply = speakReply)
    }
    fun hapticMicStart() {
        try { hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        catch (error: Exception) { CrashLog.record("VOICE", "microphone start haptic failed", error) }
    }
    val startSelectedVoice = {
        hapticMicStart()
        androidVoiceSeconds = 12
        if (settings.voiceEngineMode == VoiceEngineMode.OFFLINE) vm.startVoice()
        else vm.startAndroidVoice(settings.voiceEngineMode)
    }
    val micListening = speechSession.listening || voiceRecording
    val micSessionActive = micListening || speechSession.processing || voiceProgress != null
    LaunchedEffect(micListening, settings.voiceEngineMode) {
        if (micSessionActive) {
            for (remaining in 12 downTo 1) {
                androidVoiceSeconds = remaining
                delay(1_000)
                if (!(speechSession.listening || voiceRecording)) break
            }
            if (speechSession.listening || voiceRecording) {
                androidVoiceSeconds = 0
                vm.stopVoice()
            }
        } else androidVoiceSeconds = 12
    }
    LaunchedEffect(voiceTranscript, settings.autoSendVoiceTranscript) {
        val transcript = voiceTranscript ?: return@LaunchedEffect
        vm.clearVoiceTranscript()
        if (settings.autoSendVoiceTranscript) {
            input = ""
            submitChatCommand(transcript, speakReply = true)
        } else input = transcript
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSelectedVoice()
        else notice = "Microphone permission was not granted. Text chat still works."
    }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.submitPdf(uri)
        }
    }
    LaunchedEffect(messages.size, working) {
        val lastIndex = messages.lastIndex + if (working) 1 else 0
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }
    Scaffold(
        containerColor = AppBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(color = AppBackground, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).testTag("chat_input_bar"),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Icon(Icons.Filled.AttachFile, contentDescription = "Attach a PDF") }
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).testTag("chat_input"),
                        placeholder = { Text("Ask or give a command…") }, maxLines = 4,
                        shape = RoundedCornerShape(22.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { val value = input; input = ""; submitChatCommand(value) })
                    )
                    val activeListening = micSessionActive
                    val micPulse by rememberInfiniteTransition(label = "mic-pulse").animateFloat(
                        initialValue = 1f, targetValue = if (activeListening) 1.18f else 1f,
                        animationSpec = infiniteRepeatable(tween(520), repeatMode = RepeatMode.Reverse), label = "mic-pulse-scale"
                    )
                    IconButton(onClick = {
                        if (micSessionActive) vm.stopVoice()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startSelectedVoice()
                        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = !working) {
                        Icon(
                            if (activeListening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (activeListening) "Stop voice input" else "Speak request",
                            modifier = Modifier.size(if (activeListening) 28.dp else 24.dp).then(if (activeListening) Modifier.graphicsLayer(scaleX = micPulse, scaleY = micPulse) else Modifier),
                            tint = if (activeListening) Color(0xFFFF8C94) else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { val value = input; input = ""; submitChatCommand(value) }, enabled = input.isNotBlank() && !working) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(settings.accent))
                    }
                }
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Offline Brain", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(androidx.compose.material3.SnackbarHostState()) }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            AccessibilityOffBanner(vm)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).testTag("chat_quick_actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.send("what notifications did I get") }, modifier = Modifier.testTag("chat_quick_action_notifications"), label = { Text("Notifications") })
            }
            if (messages.isNotEmpty()) {
                TextButton(onClick = {
                    val transcript = messages.joinToString("\n\n") { "${if (it.role == "user") "You" else "Assistant"}: ${it.text}" }.take(200_000)
                    copyChatText(context, "Conversation", transcript, onResult = { notice = it })
                }, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) { Text("Copy whole conversation") }
            }
            if (macroRecording) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF302A3A)), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Recording macro: ${macroName.orEmpty()}", color = Color(0xFFFFC978), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Chat requests are being captured until you stop.", color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { vm.stopMacroRecording(save = true) }) { Text("Stop & save") }
                    }
                }
            }
            voiceStatus?.let { status ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF242538)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(status, fontSize = 13.sp, color = if (speechSession.listening || voiceRecording) Teal else Muted)
                        speechSession.errorCode?.let { Text("Android speech error code: $it", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
                        if (speechSession.listening || voiceRecording) {
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(progress = { ((12 - androidVoiceSeconds).coerceIn(0, 12)) / 12f }, modifier = Modifier.size(36.dp), color = Color(0xFFFF8C94))
                                Text("Listening · ${androidVoiceSeconds.coerceAtLeast(0)} sec left", Modifier.weight(1f), color = Teal, fontSize = 13.sp)
                            }
                            Button(onClick = vm::stopVoice, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Text("Stop listening", Modifier.padding(start = 8.dp))
                            }
                        }
                        if (speechSession.processing && voiceProgress == null) {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Done, processing…", color = Teal, fontSize = 13.sp)
                            }
                        }
                        if (voiceProgress != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { (voiceProgress ?: 0).coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("${voiceProgress}% downloaded", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                        }
                        if (status.contains("speech", true) || status.contains("voice pack", true)) {
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    .onFailure { notice = "Speech settings could not be opened on this phone. Search Settings for offline speech recognition." }
                            }) { Text("Open speech settings") }
                        }
                        if (!micSessionActive && (status.contains("try again", true) || status.contains("did not hear", true) || status.contains("could not", true) || status.contains("stopped", true) || status.contains("failed", true))) {
                            TextButton(onClick = startSelectedVoice) { Text("Retry microphone") }
                        }
                        if (!micSessionActive) {
                            TextButton(onClick = vm::clearVoiceStatus) { Text("Dismiss") }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = step != null, enter = fadeIn() + expandVertically(animationSpec = spring()), exit = fadeOut() + shrinkVertically(animationSpec = spring())) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp).animateContentSize(), colors = CardDefaults.cardColors(containerColor = Color(0xFF242538)), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        TypingIndicator(Color(settings.accent))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("Agent step", fontSize = 12.sp, color = Teal, fontWeight = FontWeight.SemiBold)
                            Text(step.orEmpty(), fontSize = 13.sp)
                        }
                        IconButton(onClick = { vm.stop() }, enabled = working) { Icon(Icons.Filled.Stop, contentDescription = "Stop task", tint = Color(0xFFFF8C94)) }
                    }
                }
            }
            if (messages.isEmpty() && !working) EmptyWelcome(settings.accent, Modifier.weight(1f).fillMaxWidth())
            else LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = messages, key = { it.id }) { message ->
                    val transferableText = ResumeEvidenceMarkup.plain(message.text)
                    MessageBubble(
                        message = message,
                        fontScale = settings.fontScale,
                        onCopy = { copyChatText(context, "Chat message", transferableText, onResult = { notice = it }) },
                        onShare = { shareChatText(context, transferableText, onError = { notice = it }) },
                        onAskDocument = { input = "ask about this document: " }
                    )
                }
                if (working) item(key = "typing") { MessageTypingBubble() }
            }
            notice?.let { Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF302A3A))) { Text(it, Modifier.padding(12.dp), fontSize = 13.sp) } }
        }
    }
    if (showFlashPermissionDialog) AlertDialog(
        onDismissRequest = { showFlashPermissionDialog = false; pendingFlashCommand = null },
        title = { Text("Allow flashlight control?") },
        text = { Text("Android requires Camera permission to switch the built-in flashlight. Cue will use it only for the flashlight command; it will not take photos or record video.") },
        confirmButton = { TextButton(onClick = { showFlashPermissionDialog = false; flashlightPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow and continue") } },
        dismissButton = { TextButton(onClick = { showFlashPermissionDialog = false; pendingFlashCommand = null; notice = "Flashlight was not changed." }) { Text("Cancel") } }
    )
    if (showPhonePermissionDialog) AlertDialog(
        onDismissRequest = { showPhonePermissionDialog = false; pendingPhoneCommand = null },
        title = { Text("Allow carrier-name lookup?") },
        text = { Text("Android requires Phone permission to reveal the mobile-network operator name. Cue will not read your call history or messages. Continue only if you want that name.") },
        confirmButton = { TextButton(onClick = { showPhonePermissionDialog = false; phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE) }) { Text("Allow and continue") } },
        dismissButton = { TextButton(onClick = { showPhonePermissionDialog = false; pendingPhoneCommand = null; notice = "Carrier name was not read." }) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyWelcome(accent: Int, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Color(accent).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Text("OB", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(accent)) }
        Spacer(Modifier.height(18.dp))
        Text("Your offline-first helper", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("I match clear requests to rules on your phone. Live public lookups run only when you ask.", color = Muted, textAlign = TextAlign.Center, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(18.dp))
        Text("Try “weather in Delhi”, “convert 10 USD to INR”, or “remind me in 10 minutes”.", color = Teal, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, fontScale: Int, onCopy: () -> Unit, onShare: () -> Unit, onAskDocument: () -> Unit) {
    val isUser = message.role == "user"
    val marker = "\n--- CLEANED TEXT ---\n"
    val isDocument = !isUser && marker in message.text
    val visibleText = if (isDocument) message.text.substringBefore(marker) else message.text
    val annotatedVisibleText = remember(visibleText) { highlightResumeEvidence(visibleText) }
    val cleanedText = if (isDocument) message.text.substringAfter(marker, "") else ""
    var showCleaned by remember(message.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f).animateContentSize(spring()),
            colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF34334A) else SurfaceDark),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (isUser) 18.dp else 5.dp, bottomEnd = if (isUser) 5.dp else 18.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                SelectionContainer { Text(annotatedVisibleText, fontSize = (14f * fontScale / 100f).sp, lineHeight = (20f * fontScale / 100f).sp) }
                if (isDocument) {
                    TextButton(onClick = { showCleaned = !showCleaned }) { Text(if (showCleaned) "Hide cleaned full text" else "Show cleaned full text") }
                    if (showCleaned) SelectionContainer { Text(cleanedText, fontSize = (13f * fontScale / 100f).sp, lineHeight = (18f * fontScale / 100f).sp) }
                    TextButton(onClick = onAskDocument) { Text("Ask a question about this document") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onShare) { Text("Share") }
                }
                Spacer(Modifier.height(5.dp))
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)), fontSize = 10.sp, color = Muted, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

private fun highlightResumeEvidence(value: String): AnnotatedString = buildAnnotatedString {
    ResumeEvidenceMarkup.segments(value).forEach { segment ->
        if (segment.highlighted) withStyle(SpanStyle(color = Color.White, background = Color(0xFF17645F), fontWeight = FontWeight.SemiBold)) {
            append(segment.text)
        } else append(segment.text)
    }
}

private fun copyChatText(context: Context, label: String, text: String, onResult: (String) -> Unit) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("Clipboard service is unavailable")
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        onResult("Copied to clipboard.")
    } catch (error: Exception) {
        CrashLog.record("CHAT", "chat text could not be copied", error)
        onResult("Could not copy that text. Check Diagnostics for details.")
    }
}

private fun shareChatText(context: Context, text: String, onError: (String) -> Unit) {
    try {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(share, "Share chat text"))
    } catch (error: Exception) {
        CrashLog.record("CHAT", "chat text could not be shared", error)
        onError("Could not open Android's share menu.")
    }
}

@Composable
private fun MessageTypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TypingIndicator(MaterialTheme.colorScheme.primary)
                Text("Working locally…", Modifier.padding(start = 8.dp), color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TypingIndicator(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = index * 140), RepeatMode.Reverse), label = "dot$index")
            Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmsScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val notice by vm.toast.collectAsStateWithLifecycle()
    val hiddenAlarmIds = remember { mutableStateListOf<Long>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var snooze by remember(settings.snoozeMinutes) { mutableFloatStateOf(settings.snoozeMinutes.toFloat()) }
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var addLabel by rememberSaveable { mutableStateOf("Reminder") }
    var addWhen by rememberSaveable { mutableStateOf(System.currentTimeMillis() + 3_600_000L) }
    val repeatingAlarms = alarms.filter { it.cron != null && it.id !in hiddenAlarmIds }
    var soundLabel by remember(settings.alarmSoundUri) { mutableStateOf(if (settings.alarmSoundUri.isBlank()) "System default" else "Custom alarm sound selected") }
    val editorLabel = remember(editing?.id) { mutableStateOf(editing?.label.orEmpty()) }
    val editorTime = remember(editing?.id) { mutableStateOf(editing?.triggerAt ?: System.currentTimeMillis()) }
    fun requestAlarmDelete(alarm: AlarmEntity) {
        if (alarm.id in hiddenAlarmIds) return
        hiddenAlarmIds.add(alarm.id)
        snackbarScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "${alarm.label} removed",
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) hiddenAlarmIds.remove(alarm.id)
            else vm.deleteAlarm(alarm.id) { deleted -> if (!deleted) hiddenAlarmIds.remove(alarm.id) }
        }
    }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            soundLabel = if (uri == null) "System default" else "Custom alarm sound selected"
            vm.setting { setAlarmSound(uri?.toString().orEmpty()) }
        }
    }
    Scaffold(
        containerColor = AppBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { CenterAlignedTopAppBar(title = { Text("Alarms") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("This page lists repeating alarms. One-time reminders are kept only until you stop them after they ring, then removed. Chat reminders use Android alarm-clock events.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Swipe a repeating alarm left or tap Delete to remove it. The Undo button keeps it if you change your mind.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Add alarm") }
                OutlinedButton(onClick = { vm.scheduleTestAlarmIn30Seconds() }, modifier = Modifier.fillMaxWidth()) { Text("Test alarm in 30 seconds") }
                notice?.let { Text(it, color = Teal, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            item {
                SettingsCard("Alarm options") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Also add one-time reminders to Clock", fontWeight = FontWeight.Medium)
                            Text("Off by default. When on, a one-time Chat reminder is also sent to your phone's Clock app. The in-app alarm and Clock alarm can both ring.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Switch(settings.mirrorAlarmsToSystemClock, { vm.setMirrorAlarmsToSystemClock(it) })
                    }
                    Text("Snooze: ${snooze.toInt()} minutes", color = Muted, fontSize = 13.sp)
                    Slider(value = snooze, onValueChange = { snooze = it }, valueRange = 1f..60f, steps = 58, onValueChangeFinished = { vm.setting { setSnoozeMinutes(snooze.toInt()) } })
                    Button(onClick = {
                        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose alarm sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.alarmSoundUri.takeIf(String::isNotBlank)?.let(Uri::parse))
                        }
                        runCatching { soundPicker.launch(picker) }.onFailure { vm.setToast("Alarm sound picker isn't available on this phone.") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Choose alarm sound · $soundLabel") }
                    Text("The alarm label is also the spoken announcement. Android may require you to allow alarm sounds and notifications.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            if (repeatingAlarms.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Text("No repeating alarms saved. One-time alarms and reminders do not stay in this list.", Modifier.padding(16.dp), color = Muted)
                    }
                }
            }
            items(repeatingAlarms, key = { it.id }) { alarm ->
                var dragOffset by remember(alarm.id) { mutableFloatStateOf(0f) }
                Card(
                    Modifier.fillMaxWidth().graphicsLayer { translationX = dragOffset }
                        .pointerInput(alarm.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (dragOffset < -84.dp.toPx()) requestAlarmDelete(alarm); dragOffset = 0f },
                                onDragCancel = { dragOffset = 0f },
                                onHorizontalDrag = { change, amount ->
                                    dragOffset = (dragOffset + amount).coerceIn(-280.dp.toPx(), 0f)
                                    change.consume()
                                }
                            )
                        }.animateContentSize(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(alarm.label, fontWeight = FontWeight.SemiBold)
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(alarm.triggerAt)), color = Muted, fontSize = 12.sp)
                                Text(repeatDescription(alarm.cron), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                            }
                            Switch(checked = alarm.enabled, onCheckedChange = { vm.setAlarmEnabled(alarm.id, it) })
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { editing = alarm }) { Text("Edit") }
                            TextButton(onClick = { requestAlarmDelete(alarm) }) { Text("Delete", color = Color(0xFFFF8C94)) }
                        }
                    }
                }
            }
        }
    }
    if (addDialog) AlertDialog(
        onDismissRequest = { addDialog = false },
        title = { Text("Add a one-time alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(addLabel, { addLabel = it }, Modifier.fillMaxWidth(), label = { Text("What should I remind you about?") }, singleLine = true)
                TextButton(onClick = {
                    val current = Calendar.getInstance().apply { timeInMillis = addWhen }
                    DatePickerDialog(context, { _, year, month, day ->
                        val chosen = Calendar.getInstance().apply { timeInMillis = addWhen; set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day) }
                        TimePickerDialog(context, { _, hour, minute ->
                            chosen.set(Calendar.HOUR_OF_DAY, hour); chosen.set(Calendar.MINUTE, minute); chosen.set(Calendar.SECOND, 0); chosen.set(Calendar.MILLISECOND, 0)
                            addWhen = chosen.timeInMillis
                        }, chosen.get(Calendar.HOUR_OF_DAY), chosen.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                    }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Time: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(addWhen))}") }
                Text("After it rings, tap Stop to remove it. The optional Clock setting may create a second alarm.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.addOneTimeAlarm(addLabel, addWhen); addDialog = false }) { Text("Set alarm") } },
        dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } }
    )
    editing?.let { alarm ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit alarm") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = editorLabel.value, onValueChange = { editorLabel.value = it }, label = { Text("Announcement text") }, singleLine = true)
                    TextButton(onClick = {
                        val current = Calendar.getInstance().apply { timeInMillis = editorTime.value }
                        DatePickerDialog(context, { _, year, month, day ->
                            val selected = Calendar.getInstance().apply {
                                timeInMillis = editorTime.value
                                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            }
                            TimePickerDialog(context, { _, hour, minute ->
                                selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute)
                                selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0)
                                editorTime.value = selected.timeInMillis
                            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("Time: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(editorTime.value))}") }
                    Text("Repeat: ${repeatDescription(alarm.cron)}", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.updateAlarm(alarm, editorLabel.value, editorTime.value); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

private fun repeatDescription(cron: String?): String {
    if (cron == null) return "One time"
    val days = cron.trim().split(Regex("\\s+")).getOrNull(4) ?: return "Repeats: $cron"
    return when (days) {
        "*" -> "Repeats every day"
        "1-5" -> "Repeats on weekdays"
        "0,6" -> "Repeats on weekends"
        "0" -> "Repeats every Sunday"
        "1" -> "Repeats every Monday"
        "2" -> "Repeats every Tuesday"
        "3" -> "Repeats every Wednesday"
        "4" -> "Repeats every Thursday"
        "5" -> "Repeats every Friday"
        "6" -> "Repeats every Saturday"
        else -> "Repeats weekly · $days"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(vm: BrainViewModel, onBack: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    var newNote by rememberSaveable { mutableStateOf("") }
    Scaffold(containerColor = AppBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CenterAlignedTopAppBar(title = { Text("Notes & to-dos") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Saved in your encrypted on-device database.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newNote, onValueChange = { newNote = it }, modifier = Modifier.weight(1f), label = { Text("New note or task") }, maxLines = 3)
                IconButton(onClick = { vm.addNote(newNote); newNote = "" }, enabled = newNote.isNotBlank()) { Icon(Icons.Filled.CheckCircle, contentDescription = "Save note", tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(12.dp))
            if (notes.isEmpty()) Text("No notes yet. Add one above or ask the chat to remember something.", color = Muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(notes, key = { it.id }) { note ->
                    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = note.done, onCheckedChange = { vm.setNoteDone(note.id, it) })
                            Text(note.text, Modifier.weight(1f).padding(horizontal = 10.dp), color = if (note.done) Muted else MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { vm.deleteNote(note.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete note", tint = Muted) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: BrainViewModel, settings: AppSettings, onDiagnostics: () -> Unit, onFeature: (String) -> Unit) {
    val context = LocalContext.current
    var city by remember(settings.defaultCity) { mutableStateOf(settings.defaultCity) }
    var phrase by remember(settings.customPhrase) { mutableStateOf(settings.customPhrase) }
    var feed by remember(settings.newsFeed) { mutableStateOf(settings.newsFeed) }
    var apiUrl by remember(settings.customApiUrl) { mutableStateOf(settings.customApiUrl) }
    var jsonPath by remember(settings.customJsonPath) { mutableStateOf(settings.customJsonPath) }
    var headers by remember(settings.customHeaders) { mutableStateOf(settings.customHeaders) }
    var scale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale.toFloat()) }
    val accentOptions = listOf(0xFFA78BFA.toInt(), 0xFF8FE3D8.toInt(), 0xFFFFC978.toInt(), 0xFF72B7FF.toInt())
    Scaffold(containerColor = AppBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CenterAlignedTopAppBar(title = { Text("Settings") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsCard("Appearance") {
                    Text("Accent colour", color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                        accentOptions.forEach { option ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(option)).then(if (settings.accent == option) Modifier.padding(5.dp).background(Color.White.copy(alpha = 0.4f), CircleShape) else Modifier)
                                .clip(CircleShape).clickableNoRipple { vm.setting { setAccent(option) } })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Chat text size: ${scale.toInt()}%", color = Muted, fontSize = 13.sp)
                    Slider(value = scale, onValueChange = { scale = it }, valueRange = 85f..125f, steps = 7, onValueChangeFinished = { vm.setting { setFontScale(scale.toInt()) } })
                }
            }
            item {
                SettingsCard("Assistant tools") {
                    Text("Teach exact phrases, define user-authored command rules, or create visible command macros.", color = Muted, fontSize = 13.sp)
                    Button(onClick = { onFeature("phrases") }, modifier = Modifier.fillMaxWidth()) { Text("Teach phrase-to-skill rules") }
                    Button(onClick = { onFeature("automation") }, modifier = Modifier.fillMaxWidth()) { Text("Automation rules and macros") }
                    Button(onClick = { onFeature("notifications") }, modifier = Modifier.fillMaxWidth()) { Text("Notification search, reply and read aloud") }
                    Button(onClick = { onFeature("clipboard") }, modifier = Modifier.fillMaxWidth()) { Text("Clipboard history (optional) ") }
                    Button(onClick = { onFeature("privacy") }, modifier = Modifier.fillMaxWidth()) { Text("Privacy, app lock and encrypted backups") }
                }
            }
            item {
                SettingsCard("Floating assistant button") {
                    Text("Optional overlay button. It starts only when you tap Start, shows a visible Stop control and keeps an ongoing Android notification while active.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    val overlayAllowed = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
                    if (!overlayAllowed) {
                        PermissionButton("Overlay permission", "Android opens the overlay settings list; select Offline Brain Agent") {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                                .onFailure { vm.setToast("Could not open overlay permission settings: ${it.message ?: it.javaClass.simpleName}") }
                        }
                    }
                    Button(onClick = {
                        runCatching { ContextCompat.startForegroundService(context, Intent(context, FloatingAssistantOverlayService::class.java)) }
                            .onFailure { vm.setToast("Floating button could not start: ${it.message ?: it.javaClass.simpleName}") }
                    }, enabled = overlayAllowed, modifier = Modifier.fillMaxWidth()) { Text("Start floating button") }
                    OutlinedButton(onClick = { runCatching { context.stopService(Intent(context, FloatingAssistantOverlayService::class.java)) } }, modifier = Modifier.fillMaxWidth()) { Text("Stop floating button") }
                }
            }
            item {
                SettingsCard("Online skills") {
                    OutlinedTextField(city, { city = it }, Modifier.fillMaxWidth(), label = { Text("Default weather city") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(feed, { feed = it }, Modifier.fillMaxWidth(), label = { Text("RSS feed URL") }, minLines = 2)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), label = { Text("Custom trigger phrase") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Text("When this phrase appears, the app can call your saved GET API. Never put a secret key in a URL you do not trust.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(apiUrl, { apiUrl = it }, Modifier.fillMaxWidth(), label = { Text("Custom API URL (GET)") }, minLines = 2)
                    OutlinedTextField(jsonPath, { jsonPath = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("JSON path, e.g. data.status") }, singleLine = true)
                    OutlinedTextField(headers, { headers = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Headers (one Name: value per line)") }, placeholder = { Text("Optional; stored encrypted and hidden after save") }, minLines = 2)
                    Button(onClick = { vm.setting { setCity(city); setFeed(feed); setPhrase(phrase); setCustomApi(apiUrl, jsonPath, headers) }; vm.setToast("Settings saved on this device.") }, modifier = Modifier.padding(top = 10.dp)) { Text("Save settings") }
                    androidx.compose.material3.TextButton(onClick = { vm.setting { clearCustomApiHeaders() }; headers = "" }) { Text("Clear saved API headers") }
                }
            }
            item {
                SettingsCard("Permissions checklist") {
                    Text("These permissions are off until you enable them in Android settings. Device automation is user-started; it does not run invisibly.", color = Muted, fontSize = 13.sp)
                    PermissionButton("Accessibility control", "Open Android accessibility settings") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    PermissionButton("Notification search", "Open notification access settings") { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    PermissionButton("Battery settings", "Review unrestricted / battery optimization settings") { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    Text("Notification contents are stored in the encrypted local database. Disable access in Android settings to stop collection. Retention cleanup is scheduled for 30 days.", color = Muted, fontSize = 12.sp)
                }
            }
            if (isXiaomiDevice()) {
                item {
                    SettingsCard("Xiaomi / Redmi background setup") {
                        Text("Some MIUI versions restrict background alarms. Review these settings manually; the app cannot change them for you.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                        PermissionButton("Open app battery settings", "App info → Battery saver → No restrictions (if available)") {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        }
                        PermissionButton("Open Android settings", "Then Apps → Permissions → Background autostart") {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                        Text("To keep the app in Recents: open Recents, long-press Offline Brain, then tap the lock icon if your MIUI version shows one. Menu names vary by version.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            item {
                SettingsCard("Diagnostics") {
                    Text("Run on-device checks for alarm delivery, notification access, accessibility, screenshot capture, OCR, network, TTS and the Vosk model.", color = Muted, fontSize = 13.sp)
                    Button(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Open Diagnostics") }
                }
            }
            item {
                SettingsCard("Voice, safety & limits") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Advanced offline Vosk", fontWeight = FontWeight.Medium)
                            Text("Optional model download; recognition uses extra storage and roughly 300 MB RAM while active.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Switch(checked = settings.useAdvancedVosk, onCheckedChange = { enabled -> vm.setting { setUseAdvancedVosk(enabled) } })
                    }
                    Text("By default, the microphone opens Android's speech recognizer and requests offline recognition. Whether it can work offline depends on the speech service installed on this phone. Replies are spoken only for voice requests.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                    Text("Alarm reminders speak independently of chat mode.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 5.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Screen reading uses Android Accessibility screenshot capture and bundled offline text recognition when enabled. Secure screens are not readable.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            item { Text("Offline Brain Agent · 1.0.5", modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center, color = Muted, fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(vm: BrainViewModel, onBack: () -> Unit) {
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

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(spring()), colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp)); content() }
    }
}

@Composable
private fun PermissionButton(title: String, description: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.fillMaxWidth()) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 11.sp) }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

private fun isXiaomiDevice(): Boolean = Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
    Build.BRAND.contains("Xiaomi", ignoreCase = true) || Build.BRAND.contains("Redmi", ignoreCase = true)
