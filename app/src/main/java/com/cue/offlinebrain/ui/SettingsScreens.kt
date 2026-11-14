package com.cue.offlinebrain.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cue.offlinebrain.device.DeviceMetric
import com.cue.offlinebrain.device.DeviceStatusSnapshotProvider
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.overlay.FloatingAssistantOverlayService
import com.cue.offlinebrain.voice.SpeechDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SettingsMuted = androidx.compose.ui.graphics.Color(0xFFAAA9B6)
private val SettingsSurface = androidx.compose.ui.graphics.Color(0xFF1A1B25)

private data class SettingsCategory(val title: String, val summary: String, val key: String, val icon: ImageVector)

@Composable
fun SettingsHomeScreen(onSelect: (String) -> Unit, onBack: () -> Unit) {
    val items = listOf(
        SettingsCategory("Appearance", "Accent colour and readable chat text size", "appearance", Icons.Filled.Settings),
        SettingsCategory("Voice and speech", "Microphone, offline voice preference and spoken replies", "voice", Icons.Filled.Mic),
        SettingsCategory("Alarms and reminders", "Add a reminder, review repeating alarms and alarm sound", "alarms", Icons.Filled.AccessTime),
        SettingsCategory("Notifications and categories", "Saved history, live capture, filters and MIUI recovery", "notifications", Icons.Filled.Notifications),
        SettingsCategory("Phrases and macros", "Teach phrases, build safe macros and open tools", "tools", Icons.Filled.TaskAlt),
        SettingsCategory("Online sources", "Weather city, news feed and optional custom API", "online", Icons.Filled.CheckCircle),
        SettingsCategory("Privacy and security", "App lock, local data and clipboard history", "privacy", Icons.Filled.CheckCircle),
        SettingsCategory("Backup and restore", "Encrypted portable and scheduled backups", "backup", Icons.Filled.AttachFile),
        SettingsCategory("Permissions and battery", "Android access, Accessibility and MIUI guidance", "permissions", Icons.Filled.Settings),
        SettingsCategory("Diagnostics", "On-device checks and full diagnostic report", "diagnostics", Icons.Filled.CheckCircle),
        SettingsCategory("About and help", "What the app does and how to get started", "about", Icons.Filled.Home)
    )
    FeatureScaffold("Settings", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose one topic. Each page keeps its own controls and a Back button.", color = SettingsMuted, fontSize = 13.sp)
            items.forEach { item ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(item.key) }, colors = CardDefaults.cardColors(containerColor = SettingsSurface), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            Text(item.summary, color = SettingsMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsHomeScreen(vm: BrainViewModel, onSelect: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metrics by remember { mutableStateOf<List<DeviceMetric>?>(null) }
    var metricsLoadError by remember { mutableStateOf(false) }
    val refreshMetrics: () -> Unit = {
        scope.launch {
            try {
                metrics = withContext(Dispatchers.IO) { DeviceStatusSnapshotProvider.read(context) }
                metricsLoadError = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("DEVICE_STATUS", "status card snapshot could not be refreshed", error)
                metricsLoadError = true
            }
        }
    }
    LaunchedEffect(context) { refreshMetrics() }
    FeatureScaffold("Tools", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccessibilityOffBanner(vm)
            DeviceStatusCard(metrics = metrics, loadError = metricsLoadError, onRefresh = refreshMetrics, context = context)
            FeatureCard("Help · what these tools do") {
                Text("Tools are optional. For example, teach “weather please” to run weather, or build a macro from a few requests. Macros run only after you review and confirm them.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            FeatureCard("Phrases and macros") {
                Text("Guided phrase rules and step-by-step macros stay on this phone.", color = SettingsMuted, fontSize = 13.sp)
                Button(onClick = { onSelect("phrases") }, modifier = Modifier.fillMaxWidth()) { Text("Teach a phrase") }
                Button(onClick = { onSelect("automation") }, modifier = Modifier.fillMaxWidth()) { Text("Build or play a macro") }
            }
            FeatureCard("Floating button") {
                Text("A small assistant button can stay over other apps while its visible notification is active. Tap it to open Chat; tap Stop to close it.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Button(onClick = { onSelect("floating") }, modifier = Modifier.fillMaxWidth()) { Text("Set up floating button") }
            }
            FeatureCard("Documents and notes") {
                Text("To summarize a PDF, open Chat and tap the paperclip. Saved notes stay on this device.", color = SettingsMuted, fontSize = 13.sp)
                OutlinedButton(onClick = { onSelect("chat") }, modifier = Modifier.fillMaxWidth()) { Text("Open Chat to attach a PDF") }
                OutlinedButton(onClick = { onSelect("notes") }, modifier = Modifier.fillMaxWidth()) { Text("Open notes and to-dos") }
                OutlinedButton(onClick = { onSelect("clipboard") }, modifier = Modifier.fillMaxWidth()) { Text("Optional clipboard history") }
            }
        }
    }
}

@Composable
fun AccessibilityOffBanner(vm: BrainViewModel) {
    val enabled by vm.accessibilityEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!enabled) FeatureCard("Accessibility is off") {
        Text("Optional screen-reading and guided actions are paused. Turn on Cue in Android Accessibility settings only if you want those tools.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
        Button(onClick = { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
    }
}

@Composable
private fun DeviceStatusCard(metrics: List<DeviceMetric>?, loadError: Boolean, onRefresh: () -> Unit, context: Context) {
    FeatureCard("Device status · this phone") {
        Text("A quick snapshot of storage, memory, battery, network and media volume. Tap a row to open related settings.", color = SettingsMuted, fontSize = 12.sp, lineHeight = 17.sp)
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
        if (metrics == null) {
            Text(if (loadError) "Device status could not be read. Tap Refresh to try again." else "Reading device status…", color = if (loadError) MaterialTheme.colorScheme.error else SettingsMuted, fontSize = 13.sp)
        } else {
            metrics.forEach { metric ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val action = metric.settingsAction
                        if (action != null) runCatching { context.startActivity(Intent(action)) }
                            .onFailure { CrashLog.record("DEVICE_STATUS", "settings page could not be opened for ${metric.key}", it) }
                    },
                    colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(deviceMetricIcon(metric.key), contentDescription = metric.label, tint = if (metric.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(metric.label, fontWeight = FontWeight.SemiBold)
                                Text(metric.value, color = if (metric.warning) MaterialTheme.colorScheme.error else SettingsMuted, fontSize = 12.sp)
                            }
                        }
                        metric.progress?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp),
                                color = if (metric.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        if (metric.note.isNotBlank()) Text(metric.note, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

private fun deviceMetricIcon(key: String): ImageVector = when (key) {
    "storage" -> Icons.Filled.Storage
    "ram" -> Icons.Filled.Memory
    "battery" -> Icons.Filled.BatteryChargingFull
    "network" -> Icons.Filled.Wifi
    else -> Icons.Filled.VolumeUp
}

@Composable
fun FloatingButtonSetupScreen(vm: BrainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val allowed = remember { mutableStateOf(runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) }
    val toast by vm.toast.collectAsStateWithLifecycle()
    FeatureScaffold("Floating button setup", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Help · example") {
                Text("The floating button is a shortcut to Chat while you are using another app. Example: tap it over WhatsApp, choose Open Chat, then type a request. It does not read or tap other apps by itself.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            FeatureCard("Step 1 · Allow the button to appear") {
                Text(if (allowed.value) "Overlay permission is on." else "Android needs your permission before the button can appear over other apps.", color = SettingsMuted, fontSize = 13.sp)
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                        .onFailure { vm.setToast("Could not open overlay settings: ${it.message ?: it.javaClass.simpleName}") }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (allowed.value) "Review overlay permission" else "Open overlay permission settings") }
                Text("After returning, use the Test button below to check whether Android saved the permission.", color = SettingsMuted, fontSize = 12.sp)
            }
            FeatureCard("Step 2 · Start and test") {
                Button(onClick = {
                    allowed.value = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
                    if (!allowed.value) vm.setToast("Overlay permission is still off. Enable it in Android settings first.")
                    else {
                        runCatching { ContextCompat.startForegroundService(context, Intent(context, FloatingAssistantOverlayService::class.java)) }
                            .onSuccess { vm.setToast("Floating button started. Look near the top-right of the screen.") }
                            .onFailure { CrashLog.record("OVERLAY", "user-started overlay failed", it); vm.setToast("Floating button could not start: ${it.message ?: it.javaClass.simpleName}") }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Test and start floating button") }
                OutlinedButton(onClick = {
                    allowed.value = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
                    if (!allowed.value) vm.setToast("Overlay permission is still off. Enable it in Android settings first.")
                    else runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, FloatingAssistantOverlayService::class.java).setAction(FloatingAssistantOverlayService.ACTION_TEST_BUBBLE)
                        )
                    }.onSuccess { vm.setToast("Showing a test bubble. It will close automatically after 10 seconds.") }
                        .onFailure {
                            CrashLog.record("OVERLAY", "user-started 10-second bubble test failed", it)
                            vm.setToast("Bubble test could not start: ${it.message ?: it.javaClass.simpleName}")
                        }
                }, modifier = Modifier.fillMaxWidth()) { Text("Show bubble for 10 seconds") }
                OutlinedButton(onClick = { context.stopService(Intent(context, FloatingAssistantOverlayService::class.java)); vm.setToast("Floating button stopped.") }, modifier = Modifier.fillMaxWidth()) { Text("Stop floating button") }
                toast?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
            }
            FeatureCard("Step 3 · How to use and stop it") {
                Text("Tap “OB · Assistant” to expand it, then tap “Open Chat”. Stop it using the panel's Stop button or the persistent Android notification. It stops when you choose Stop; it does not run a hidden assistant.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun AppearanceSettingsScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    var scale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale.toFloat()) }
    val colors = listOf(0xFFA78BFA.toInt(), 0xFF8FE3D8.toInt(), 0xFFFFC978.toInt(), 0xFF72B7FF.toInt())
    FeatureScaffold("Appearance", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Make text easier to read") {
                Text("Choose an accent colour and adjust chat text size. Example: move the slider right if replies look too small.", color = SettingsMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { colors.forEach { color -> Button(onClick = { vm.setting { setAccent(color) } }) { Text(if (settings.accent == color) "Selected" else "Colour") } } }
                Text("Chat text size: ${scale.toInt()}%", color = SettingsMuted)
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 85f..125f, steps = 7, onValueChangeFinished = { vm.setting { setFontScale(scale.toInt()) } })
            }
        }
    }
}

@Composable
fun VoiceSettingsScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    FeatureScaffold("Voice and speech", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Help · speak a request") {
                Text("By default, the microphone listens inside Chat and shows words as the phone recognizes them. Example: say “weather in Delhi”; English (India) is preferred, with English (US) fallback. Offline recognition depends on the speech service and installed voice pack.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Text("Last speech error: ${SpeechDiagnostics.lastError}", color = SettingsMuted, fontSize = 12.sp, lineHeight = 17.sp)
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }.onFailure { vm.setToast("Speech settings could not be opened. Search Android Settings for speech or offline voice packs.") } }, modifier = Modifier.fillMaxWidth()) { Text("Open Android speech settings") }
            }
            FeatureCard("What happens after speech?") {
                Text(if (settings.autoSendVoiceTranscript) "On: recognized words are sent as soon as the phone finishes listening." else "Off by default: recognized words are placed in Chat so you can review or edit them before sending.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (settings.autoSendVoiceTranscript) "Send automatically" else "Review before sending", Modifier.weight(1f))
                    Switch(settings.autoSendVoiceTranscript, { vm.setting { setAutoSendVoiceTranscript(it) } })
                }
            }
            FeatureCard("Advanced offline Vosk") {
                Text("Optional. It may download an English model the first time you tap the microphone, uses extra storage and about 300 MB of memory while listening. Leave it off to use Android's recognizer.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.useAdvancedVosk) "On" else "Off", Modifier.weight(1f)); Switch(settings.useAdvancedVosk, { enabled -> vm.setting { setUseAdvancedVosk(enabled) } }) }
            }
        }
    }
}

@Composable
fun OnlineSettingsScreen(vm: BrainViewModel, settings: AppSettings, onBack: () -> Unit) {
    var city by remember(settings.defaultCity) { mutableStateOf(settings.defaultCity) }
    var feed by remember(settings.newsFeed) { mutableStateOf(settings.newsFeed) }
    var phrase by remember(settings.customPhrase) { mutableStateOf(settings.customPhrase) }
    var apiUrl by remember(settings.customApiUrl) { mutableStateOf(settings.customApiUrl) }
    var jsonPath by remember(settings.customJsonPath) { mutableStateOf(settings.customJsonPath) }
    var headers by remember(settings.customHeaders) { mutableStateOf(settings.customHeaders) }
    FeatureScaffold("Online sources", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Help · live lookups") {
                Text("Weather, headlines and custom APIs use the internet only when you ask or when you turn on scheduled refresh. Example: “weather in Delhi” runs a live weather lookup.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedTextField(city, { city = it }, Modifier.fillMaxWidth(), label = { Text("Default weather city") }, singleLine = true)
                OutlinedTextField(feed, { feed = it }, Modifier.fillMaxWidth(), label = { Text("RSS feed URL") }, minLines = 2)
                OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), label = { Text("Custom trigger phrase") }, singleLine = true)
                OutlinedTextField(apiUrl, { apiUrl = it }, Modifier.fillMaxWidth(), label = { Text("Custom API URL (GET)") }, minLines = 2)
                OutlinedTextField(jsonPath, { jsonPath = it }, Modifier.fillMaxWidth(), label = { Text("JSON path, e.g. data.status") }, singleLine = true)
                OutlinedTextField(headers, { headers = it }, Modifier.fillMaxWidth(), label = { Text("Optional headers, one per line") }, minLines = 2)
                Text("Only save API headers for a URL you trust. They are encrypted on this phone.", color = SettingsMuted, fontSize = 12.sp)
                Button(onClick = { vm.setting { setCity(city); setFeed(feed); setPhrase(phrase); setCustomApi(apiUrl, jsonPath, headers) }; vm.setToast("Online-source settings saved on this phone.") }, modifier = Modifier.fillMaxWidth()) { Text("Save sources") }
                OutlinedButton(onClick = { vm.setting { clearCustomApiHeaders() }; headers = "" }, modifier = Modifier.fillMaxWidth()) { Text("Clear saved API headers") }
            }
            FeatureCard("Scheduled refresh") {
                Text("Optional weather, headlines and your saved custom API refresh about every 12 hours when Android allows. It may run later than planned.", color = SettingsMuted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.backgroundRefreshEnabled) "On" else "Off", Modifier.weight(1f)); Switch(settings.backgroundRefreshEnabled, { vm.setBackgroundRefreshEnabled(it) }) }
            }
        }
    }
}

@Composable
fun PermissionsSettingsScreen(vm: BrainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    FeatureScaffold("Permissions and battery", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Help · optional access") {
                Text("Permissions stay off until you choose them. For example, Notification Access is only needed to save new alerts; Accessibility is only needed for the optional screen-reading tools. You can turn either off in Android settings.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Button(onClick = { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
                Button(onClick = { openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Open Notification Access") }
                Button(onClick = { openSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Review battery restrictions") }
                Button(onClick = { openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Review floating-button permission") }
            }
            if (android.os.Build.MANUFACTURER.contains("Xiaomi", true) || android.os.Build.BRAND.contains("Redmi", true)) {
                FeatureCard("Redmi / MIUI background help") {
                    Text("For the floating button, allow Cue to display pop-up windows while running in the background and to show on the lock screen. Try Settings → Apps → Manage apps → Cue → Other permissions. Menu names can vary by MIUI version. These switches are separate from Android's regular “Display over other apps” permission.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    Button(onClick = { openMiuiPermissionEditor(context, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Open MIUI app permissions") }
                    Text("If the shortcut does not open the right page, use App info below, then choose Other permissions. For notification capture or delayed background work, also review battery restrictions and Background autostart.", color = SettingsMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    Button(onClick = { openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, vm, Uri.parse("package:${context.packageName}")) }, modifier = Modifier.fillMaxWidth()) { Text("Open this app's settings") }
                    OutlinedButton(onClick = { openSettings(context, Settings.ACTION_SETTINGS, vm) }, modifier = Modifier.fillMaxWidth()) { Text("Open Android settings") }
                }
            }
        }
    }
}

@Composable
fun AboutHelpScreen(onBack: () -> Unit) {
    FeatureScaffold("About and help", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Offline Brain Agent · 1.0.5") {
                Text("This app matches requests to fixed on-device rules. It does not use a chat LLM. Some live lookups use the internet only when asked or when scheduled refresh is enabled.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Text("All user records stay in the app's encrypted local database unless you export an encrypted backup.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Text("For app help or to report a bug, use the Diagnostics page to copy or share a report.", color = SettingsMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

private fun openSettings(context: Context, action: String, vm: BrainViewModel, uri: Uri? = null) {
    try {
        context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: Exception) {
        CrashLog.record("PERMISSIONS", "could not open Android settings action=$action", error)
        vm.setToast("Android settings could not be opened: ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun openMiuiPermissionEditor(context: Context, vm: BrainViewModel) {
    try {
        context.startActivity(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .putExtra("extra_pkgname", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (error: Exception) {
        CrashLog.record("PERMISSIONS", "MIUI permission editor unavailable; opening App info fallback", error)
        openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, vm, Uri.parse("package:${context.packageName}"))
    }
}
