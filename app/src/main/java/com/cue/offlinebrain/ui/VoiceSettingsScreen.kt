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
fun VoiceSettingsScreen(feature: VoiceViewModel, settings: AppSettings, onBack: () -> Unit) {
    val vm = feature.coordinator
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
