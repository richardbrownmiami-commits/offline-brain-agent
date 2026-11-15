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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import com.cue.offlinebrain.ui.RestoreRecoveryScreen
import com.cue.offlinebrain.ui.ChatScreen
import com.cue.offlinebrain.ui.NotificationAlertHost
import com.cue.offlinebrain.ui.DashboardScreen
import com.cue.offlinebrain.ui.AlarmsScreen
import com.cue.offlinebrain.ui.NotesScreen
import com.cue.offlinebrain.ui.DiagnosticsScreen
import com.cue.offlinebrain.ui.BrainViewModel
import com.cue.offlinebrain.ui.ChatViewModel
import com.cue.offlinebrain.ui.AlarmViewModel
import com.cue.offlinebrain.ui.NotificationViewModel
import com.cue.offlinebrain.ui.AutomationViewModel
import com.cue.offlinebrain.ui.SecurityViewModel
import com.cue.offlinebrain.ui.VoiceViewModel
import com.cue.offlinebrain.ui.DiagnosticsViewModel
import com.cue.offlinebrain.ui.FeatureViewModelFactory
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
import com.cue.offlinebrain.notifications.NotificationAccessState
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
import kotlinx.coroutines.flow.first


class MainActivity : FragmentActivity() {
    private val brainViewModel: BrainViewModel by viewModels()
    private val featureFactory by lazy { FeatureViewModelFactory(brainViewModel) }
    private val chatFeature: ChatViewModel by viewModels { featureFactory }
    private val alarmFeature: AlarmViewModel by viewModels { featureFactory }
    private val notificationFeature: NotificationViewModel by viewModels { featureFactory }
    private val automationFeature: AutomationViewModel by viewModels { featureFactory }
    private val securityFeature: SecurityViewModel by viewModels { featureFactory }
    private val voiceFeature: VoiceViewModel by viewModels { featureFactory }
    private val diagnosticsFeature: DiagnosticsViewModel by viewModels { featureFactory }
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
                else OfflineBrainApp(
                    brainViewModel, chatFeature, alarmFeature, notificationFeature,
                    automationFeature, securityFeature, voiceFeature, diagnosticsFeature
                )
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
        lifecycleScope.launch {
            val app = application as OfflineBrainApplication
            val prefs = app.settings.settings.first()
            NotificationAccessState.appResumed(this@MainActivity, requestBinding = prefs.notificationLiveCaptureEnabled)
        }
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
    override fun onStop() {
        NotificationAccessState.appBackgrounded(applicationContext)
        super.onStop()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineBrainApp(
    vm: BrainViewModel,
    chatFeature: ChatViewModel,
    alarmFeature: AlarmViewModel,
    notificationFeature: NotificationViewModel,
    automationFeature: AutomationViewModel,
    securityFeature: SecurityViewModel,
    voiceFeature: VoiceViewModel,
    diagnosticsFeature: DiagnosticsViewModel
) {
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
                AppLockScreen(securityFeature, onUnlocked = { AppLockLifecycle.markUnlocked(); unlocked = true }, onBiometric = { success ->
                    val activity = activityContext as? MainActivity
                    activity?.showBiometricUnlock(success) ?: vm.setToast("System biometric unlock is not available here.")
                })
            } else {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val current = entry?.destination?.route ?: "home"
        LaunchedEffect(settings.selfCheckSeen, current) {
            if (settings.selfCheckSeen && current == "self-check") {
                nav.navigate("home") { popUpTo("self-check") { inclusive = true }; launchSingleTop = true }
            }
        }
        Scaffold(
            containerColor = AppBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (current != "chat" || !WindowInsets.isImeVisible) {
                    NavigationBar(containerColor = SurfaceDark, modifier = Modifier.navigationBarsPadding().testTag("bottom_navigation")) {
                        NavigationBarItem(selected = current in setOf("home", "chat"), onClick = { nav.navigate("home") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                        NavigationBarItem(selected = current == "notifications", onClick = { nav.navigate("notifications") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.Notifications, null) }, label = { Text("Notifications") })
                        NavigationBarItem(selected = current == "settings" || current.startsWith("settings/") || current in setOf("alarms", "diagnostics", "privacy"), onClick = { nav.navigate("settings") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
                        NavigationBarItem(selected = current in setOf("tools", "phrases", "automation", "floating", "notes", "clipboard"), onClick = { nav.navigate("tools") { launchSingleTop = true; popUpTo(nav.graph.findStartDestination().id) { saveState = true }; restoreState = true } }, icon = { Icon(Icons.Filled.TaskAlt, null) }, label = { Text("Tools") })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = "self-check", modifier = Modifier.padding(padding)) {
                composable("self-check") {
                    FirstRunSelfCheckScreen(
                        vm = vm,
                        onTryInput = { vm.setting { markSelfCheckSeen() }; nav.navigate("chat") { popUpTo("self-check") { inclusive = true }; launchSingleTop = true } },
                        onPermissions = { nav.navigate("settings") },
                        onContinue = { vm.setting { markSelfCheckSeen() }; nav.navigate("home") { popUpTo("self-check") { inclusive = true }; launchSingleTop = true } }
                    )
                }
                composable("home") { DashboardScreen(vm = vm, onOpenChat = { nav.navigate("chat") { launchSingleTop = true } }) }
                composable("chat") { ChatScreen(chatFeature, settings) }
                composable("alarms") { AlarmsScreen(alarmFeature, settings) { nav.popBackStack() } }
                composable("notes") { NotesScreen(vm) { nav.popBackStack() } }
                composable("settings") {
                    SettingsHomeScreen(onSelect = { section ->
                        val destination = when (section) {
                            "alarms", "tools", "privacy", "diagnostics" -> section
                            "notifications" -> "settings/notifications"
                            else -> "settings/$section"
                        }
                        nav.navigate(destination) { launchSingleTop = true }
                    }, onBack = { nav.popBackStack() })
                }
                composable("settings/{section}") { backStackEntry ->
                    val section = backStackEntry.arguments?.getString("section").orEmpty()
                    when (section) {
                        "notifications" -> NotificationsScreen(notificationFeature, settings) { nav.popBackStack() }
                        "appearance" -> AppearanceSettingsScreen(vm, settings) { nav.popBackStack() }
                        "voice" -> VoiceSettingsScreen(voiceFeature, settings) { nav.popBackStack() }
                        "online" -> OnlineSettingsScreen(vm, settings) { nav.popBackStack() }
                        "backup" -> PrivacyScreen(securityFeature, settings, { nav.popBackStack() }, section = "backup")
                        "permissions" -> PermissionsSettingsScreen(vm) { nav.popBackStack() }
                        else -> AboutHelpScreen { nav.popBackStack() }
                    }
                }
                composable("tools") { ToolsHomeScreen(vm, onSelect = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
                composable("floating") { FloatingButtonSetupScreen(vm) { nav.popBackStack() } }
                composable("diagnostics") { DiagnosticsScreen(diagnosticsFeature) { nav.popBackStack() } }
                composable("phrases") { PhraseTeachingScreen(vm, settings) { nav.popBackStack() } }
                composable("automation") { AutomationScreen(automationFeature) { nav.popBackStack() } }
                composable("notifications") { NotificationsScreen(notificationFeature, settings) { nav.popBackStack() } }
                composable("clipboard") { ClipboardScreen(vm, settings) { nav.popBackStack() } }
                composable("privacy") { PrivacyScreen(securityFeature, settings, { nav.popBackStack() }, section = "security") }
            }
            NotificationAlertHost(settings, Modifier.align(Alignment.TopCenter))
            }
        }
            }
        }
    }
}
