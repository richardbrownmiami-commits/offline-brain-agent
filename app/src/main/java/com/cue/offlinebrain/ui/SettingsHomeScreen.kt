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

@Composable
fun SettingsHomeScreen(onSelect: (String) -> Unit, onBack: () -> Unit) {
    val items = listOf(
        SettingsCategory("Appearance", "Accent colour and readable chat text size", "appearance", Icons.Filled.Settings),
        SettingsCategory("Voice and speech", "Microphone, offline voice preference and spoken replies", "voice", Icons.Filled.Mic),
        SettingsCategory("Alarm settings and reminders", "Add a reminder, review repeating alarms and alarm sound", "alarms", Icons.Filled.AccessTime),
        SettingsCategory("Notification settings", "Saved history, live capture, filters and MIUI recovery", "notifications", Icons.Filled.Notifications),
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
