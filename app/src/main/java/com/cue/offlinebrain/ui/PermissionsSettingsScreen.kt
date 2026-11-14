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
