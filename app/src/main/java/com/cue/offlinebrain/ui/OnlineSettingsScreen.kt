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
