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
