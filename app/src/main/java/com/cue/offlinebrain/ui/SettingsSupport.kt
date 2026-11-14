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

internal val SettingsMuted = androidx.compose.ui.graphics.Color(0xFFAAA9B6)
internal val SettingsSurface = androidx.compose.ui.graphics.Color(0xFF1A1B25)

internal data class SettingsCategory(val title: String, val summary: String, val key: String, val icon: ImageVector)

@Composable
internal fun DeviceStatusCard(metrics: List<DeviceMetric>?, loadError: Boolean, onRefresh: () -> Unit, context: Context) {
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

internal fun deviceMetricIcon(key: String): ImageVector = when (key) {
    "storage" -> Icons.Filled.Storage
    "ram" -> Icons.Filled.Memory
    "battery" -> Icons.Filled.BatteryChargingFull
    "network" -> Icons.Filled.Wifi
    else -> Icons.Filled.VolumeUp
}

internal fun openSettings(context: Context, action: String, vm: BrainViewModel, uri: Uri? = null) {
    try {
        context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: Exception) {
        CrashLog.record("PERMISSIONS", "could not open Android settings action=$action", error)
        vm.setToast("Android settings could not be opened: ${error.message ?: error.javaClass.simpleName}")
    }
}

internal fun openMiuiPermissionEditor(context: Context, vm: BrainViewModel) {
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
