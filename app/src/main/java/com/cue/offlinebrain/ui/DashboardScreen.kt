package com.cue.offlinebrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cue.offlinebrain.BuildConfig
import com.cue.offlinebrain.device.DeviceMetric
import com.cue.offlinebrain.device.DeviceStatusSnapshotProvider
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(vm: BrainViewModel, onOpenChat: () -> Unit) {
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
                CrashLog.record("DEVICE_STATUS", "dashboard status snapshot could not be refreshed", error)
                metricsLoadError = true
            }
        }
    }
    LaunchedEffect(context) { refreshMetrics() }

    FeatureScaffold(title = "Home", onBack = {}, showBack = false) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccessibilityOffBanner(vm)
            FeatureCard("Your offline-first assistant") {
                Text(
                    "Ask Cue to help with phone tasks, reminders, saved notifications, or a document. Chat uses built-in local rules; no LLM writes its replies.",
                    color = SettingsMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth().testTag("dashboard_open_chat")) {
                    Icon(Icons.Filled.Chat, contentDescription = null)
                    Text("Open Chat", Modifier.padding(start = 8.dp))
                }
            }
            DeviceStatusCard(metrics = metrics, loadError = metricsLoadError, onRefresh = refreshMetrics, context = context)
            FeatureCard("About this app") {
                Text(
                    "Version ${BuildConfig.VERSION_NAME} · Your device status and saved app data are handled on this phone. Optional live lookups run only when requested; permissions stay under your control.",
                    color = SettingsMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
