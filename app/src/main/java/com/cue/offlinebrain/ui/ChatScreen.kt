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
import com.cue.offlinebrain.data.BrainReply
import com.cue.offlinebrain.data.BrainReplyCodec
import com.cue.offlinebrain.data.ReplyNotificationItem
import com.cue.offlinebrain.data.brainReplyFromLegacyText
import com.cue.offlinebrain.notifications.NotificationReplyRegistry
import com.cue.offlinebrain.data.NoteEntity
import com.cue.offlinebrain.data.ClipboardEntryEntity
import com.cue.offlinebrain.data.ClipboardCapturePolicy
import com.cue.offlinebrain.summary.ResumeEvidenceMarkup
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.voice.SpeechDiagnostics
import com.cue.offlinebrain.voice.VoiceEngineMode
import com.cue.offlinebrain.voice.VoiceSpeechOutput
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
internal fun ChatScreen(feature: ChatViewModel, settings: AppSettings) {
    val vm = feature.coordinator
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
    val savedNotifications by vm.notificationHistory.collectAsStateWithLifecycle()
    val readNotificationIds = remember(savedNotifications) { savedNotifications.asSequence().filter { it.isRead }.mapTo(hashSetOf()) { it.id } }
    var input by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostView = LocalView.current
    var androidVoiceSeconds by rememberSaveable { mutableStateOf(12) }
    var pendingFlashCommand by rememberSaveable { mutableStateOf<String?>(null) }
    var showFlashPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPhoneCommand by rememberSaveable { mutableStateOf<String?>(null) }
    var showPhonePermissionDialog by rememberSaveable { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<ReplyNotificationItem?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }
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
                    val decodedReply = if (message.role == "assistant") {
                        BrainReplyCodec.decode(message.replyJson) ?: brainReplyFromLegacyText(message.text)
                    } else null
                    val typedReply = if (decodedReply is BrainReply.NotificationList) {
                        decodedReply.copy(items = decodedReply.items.map { if (it.id in readNotificationIds) it.copy(isRead = true) else it })
                    } else decodedReply
                    val transferableText = ResumeEvidenceMarkup.plain(typedReply?.plainText ?: message.text)
                    fun openNotificationApp(item: ReplyNotificationItem, toReply: Boolean) {
                        val appName = runCatching {
                            val info = context.packageManager.getApplicationInfo(item.packageName, 0)
                            context.packageManager.getApplicationLabel(info).toString()
                        }.getOrDefault(item.packageName.substringAfterLast('.'))
                        val intent = context.packageManager.getLaunchIntentForPackage(item.packageName)
                        if (intent == null) notice = "Couldn't open $appName because Android found no launchable app."
                        else runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onSuccess {
                                notice = if (toReply) "Opened $appName. Saved notifications don't retain Android's inline reply action, so reply in the app."
                                else "Opened $appName."
                            }
                            .onFailure { notice = "Couldn't open $appName: ${it.message ?: it.javaClass.simpleName}." }
                    }
                    MessageBubble(
                        message = message,
                        reply = typedReply,
                        fontScale = settings.fontScale,
                        onCopy = { copyChatText(context, "Chat message", transferableText, onResult = { notice = it }) },
                        onShare = { shareChatText(context, transferableText, onError = { notice = it }) },
                        onAskDocument = { input = "ask about this document: " },
                        onOpenApp = { item -> openNotificationApp(item, toReply = false) },
                        onReply = { item ->
                            if (NotificationReplyRegistry.canReply(item.packageName, item.postedAt)) {
                                replyTarget = item
                                replyText = ""
                            } else openNotificationApp(item, toReply = true)
                        },
                        onReadAloud = { item ->
                            val spoken = listOf(item.title, item.text).filter(String::isNotBlank).joinToString(". ")
                            if (spoken.isBlank()) notice = "There is no saved notification text to read aloud."
                            else scope.launch { VoiceSpeechOutput.speakOnce(context, spoken) }
                        },
                        onCopyNotification = { item ->
                            val copied = listOf(item.title, item.text).filter(String::isNotBlank).joinToString("\n")
                            copyChatText(context, "Saved notification", copied, onResult = { notice = it })
                        },
                        onMarkRead = vm::markNotificationRead
                    )
                }
                if (working) item(key = "typing") { MessageTypingBubble() }
            }
            notice?.let { Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF302A3A))) { Text(it, Modifier.padding(12.dp), fontSize = 13.sp) } }
        }
    }
    replyTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { replyTarget = null },
            title = { Text("Reply to ${item.title}") },
            text = { OutlinedTextField(replyText, { replyText = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("Reply text") }, maxLines = 4) },
            confirmButton = { TextButton(enabled = replyText.isNotBlank(), onClick = {
                val sent = NotificationReplyRegistry.reply(context, item.packageName, item.postedAt, replyText)
                notice = if (sent) "Reply sent via the notification's active reply action." else "That notification's reply action expired or is unavailable."
                replyTarget = null
            }) { Text("Send reply") } },
            dismissButton = { TextButton(onClick = { replyTarget = null }) { Text("Cancel") } }
        )
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
private fun MessageBubble(
    message: MessageEntity,
    reply: BrainReply?,
    fontScale: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAskDocument: () -> Unit,
    onOpenApp: (ReplyNotificationItem) -> Unit,
    onReply: (ReplyNotificationItem) -> Unit,
    onReadAloud: (ReplyNotificationItem) -> Unit,
    onCopyNotification: (ReplyNotificationItem) -> Unit,
    onMarkRead: (Long) -> Unit
) {
    val isUser = message.role == "user"
    val marker = "\n--- CLEANED TEXT ---\n"
    val documentReply = reply as? BrainReply.DocumentSummary
    val isDocument = !isUser && (documentReply != null || marker in message.text)
    val visibleText = when (reply) {
        is BrainReply.DeviceStatusCard -> reply.title
        is BrainReply.NotificationSummaryCard -> reply.title
        is BrainReply.NotificationList -> reply.title
        is BrainReply.DocumentSummary -> reply.summary
        is BrainReply.Text -> reply.text
        null -> if (isDocument) message.text.substringBefore(marker) else message.text
    }
    val annotatedVisibleText = remember(visibleText) { highlightResumeEvidence(visibleText) }
    val cleanedText = documentReply?.cleanedText ?: if (isDocument) message.text.substringAfter(marker, "") else ""
    var showCleaned by remember(message.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f).animateContentSize(spring()),
            colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF34334A) else SurfaceDark),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (isUser) 18.dp else 5.dp, bottomEnd = if (isUser) 5.dp else 18.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                when (reply) {
                    is BrainReply.DeviceStatusCard -> DeviceStatusCardContent(reply, fontScale)
                    is BrainReply.NotificationSummaryCard -> NotificationSummaryCardContent(reply)
                    is BrainReply.NotificationList -> NotificationListCardContent(
                        reply, onOpenApp, onReply, onReadAloud, onCopyNotification, onMarkRead
                    )
                    else -> SelectionContainer { Text(annotatedVisibleText, fontSize = (14f * fontScale / 100f).sp, lineHeight = (20f * fontScale / 100f).sp) }
                }
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

@Composable
private fun DeviceStatusCardContent(reply: BrainReply.DeviceStatusCard, fontScale: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(reply.title, fontSize = (16f * fontScale / 100f).sp, fontWeight = FontWeight.SemiBold, color = Teal)
        reply.rows.forEach { row ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, Modifier.weight(1f), fontSize = (13f * fontScale / 100f).sp, color = Muted)
                    Text(row.value, fontSize = (13f * fontScale / 100f).sp, fontWeight = FontWeight.Medium)
                }
                row.progressPercent?.let { percent ->
                    LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        reply.note?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, color = Muted) }
    }
}

@Composable
private fun NotificationSummaryCardContent(reply: BrainReply.NotificationSummaryCard) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(reply.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Teal)
        Text("${reply.count} saved · ${reply.unreadCount} unread", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        reply.categoryCounts.take(6).forEach { count ->
            Text("${count.category}: ${count.count}", fontSize = 13.sp, color = Muted)
        }
        reply.latestAt?.let { at ->
            Text("Latest: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))}", fontSize = 12.sp, color = Muted)
        }
        reply.note?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, color = Muted) }
    }
}

@Composable
private fun NotificationListCardContent(
    reply: BrainReply.NotificationList,
    onOpenApp: (ReplyNotificationItem) -> Unit,
    onReply: (ReplyNotificationItem) -> Unit,
    onReadAloud: (ReplyNotificationItem) -> Unit,
    onCopy: (ReplyNotificationItem) -> Unit,
    onMarkRead: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(reply.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Teal)
        Text("${reply.totalCount} saved · ${reply.unreadCount} unread", fontSize = 13.sp, color = Muted)
        reply.items.forEach { item -> NotificationItemCard(item, onOpenApp, onReply, onReadAloud, onCopy, onMarkRead) }
        reply.note?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, color = Muted) }
    }
}

@Composable
private fun NotificationItemCard(
    item: ReplyNotificationItem,
    onOpenApp: (ReplyNotificationItem) -> Unit,
    onReply: (ReplyNotificationItem) -> Unit,
    onReadAloud: (ReplyNotificationItem) -> Unit,
    onCopy: (ReplyNotificationItem) -> Unit,
    onMarkRead: (Long) -> Unit
) {
    var markedRead by rememberSaveable(item.id, item.isRead) { mutableStateOf(item.isRead) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF242538)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.title + if (item.duplicateCount > 1) " x${item.duplicateCount}" else "", fontWeight = FontWeight.SemiBold)
            Text("${item.category} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.postedAt))}", color = Muted, fontSize = 11.sp)
            if (item.groupName.isNotBlank()) Text(item.groupName, color = Muted, fontSize = 12.sp)
            if (item.text.isNotBlank()) SelectionContainer { Text(item.text, fontSize = 13.sp, lineHeight = 18.sp) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onOpenApp(item) }) { Text("Open app") }
                TextButton(onClick = { onReply(item) }) { Text("Reply") }
                TextButton(onClick = { onReadAloud(item) }) { Text("Read aloud") }
                TextButton(onClick = { onCopy(item) }) { Text("Copy") }
                TextButton(onClick = { onMarkRead(item.id); markedRead = true }, enabled = !markedRead) { Text(if (markedRead) "Read" else "Mark read") }
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
