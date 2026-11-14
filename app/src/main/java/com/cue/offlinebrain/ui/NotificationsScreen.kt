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
fun NotificationsScreen(feature: NotificationViewModel, settings: AppSettings, onBack: () -> Unit) {
    val vm = feature.coordinator
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
