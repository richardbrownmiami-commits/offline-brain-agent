package com.cue.offlinebrain.ui

import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.AppBackground
import com.cue.offlinebrain.SurfaceDark
import com.cue.offlinebrain.Muted
import com.cue.offlinebrain.Teal
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

@Composable
internal fun RestoreRecoveryScreen(app: OfflineBrainApplication, issue: String) {
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
