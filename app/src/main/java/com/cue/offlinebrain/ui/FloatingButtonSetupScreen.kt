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
