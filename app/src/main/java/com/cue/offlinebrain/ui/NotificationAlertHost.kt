package com.cue.offlinebrain.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.drawable.toBitmap
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.notifications.NotificationAlert
import com.cue.offlinebrain.notifications.NotificationAlertQueue
import com.cue.offlinebrain.notifications.NotificationPrivacyFormatter
import com.cue.offlinebrain.notifications.NotificationReplyRegistry
import com.cue.offlinebrain.notifications.PackageLabelResolver
import com.cue.offlinebrain.voice.VoiceSpeechOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import android.text.format.DateUtils

@Composable
internal fun NotificationAlertHost(settings: AppSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val alerts by NotificationAlertQueue.pending.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var replyTarget by remember { mutableStateOf<NotificationAlert?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_STOP -> { resumed = false; NotificationAlertQueue.clear() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(alerts.firstOrNull()?.id, resumed, settings.notificationInAppAlertsEnabled, settings.notificationSpeakNewEnabled) {
        if (!settings.notificationInAppAlertsEnabled && !settings.notificationSpeakNewEnabled) NotificationAlertQueue.clear()
        val alert = alerts.firstOrNull()
        if (resumed && alert != null && (settings.notificationInAppAlertsEnabled || settings.notificationSpeakNewEnabled)) {
            if (settings.notificationSpeakNewEnabled) {
                val preview = safePreview(context, alert)
                scope.launch { runCatching { VoiceSpeechOutput.speakOnce(context, preview, takeAudioFocus = true) }.onFailure { CrashLog.record("NOTIFICATIONS", "automatic new-alert speech failed", it) } }
            }
            delay(6_000L)
            NotificationAlertQueue.dismiss(alert.id)
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val visible = resumed && settings.notificationInAppAlertsEnabled
        val alert = alerts.firstOrNull()
        if (visible && alert != null) {
            NotificationBanner(
                alert = alert,
                context = context,
                onDismiss = { NotificationAlertQueue.dismiss(alert.id) },
                onReply = { replyTarget = alert; replyText = "" },
                onReadAloud = {
                    val preview = safePreview(context, alert)
                    scope.launch { runCatching { VoiceSpeechOutput.speakOnce(context, preview, takeAudioFocus = true) }.onFailure { CrashLog.record("NOTIFICATIONS", "banner read-aloud failed", it) } }
                }
            )
        }
    }
    replyTarget?.let { alert ->
        val canReply = NotificationReplyRegistry.canReply(alert.packageName, alert.postedAt)
        AlertDialog(
            onDismissRequest = { replyTarget = null },
            title = { Text("Reply to ${alert.title.ifBlank { PackageLabelResolver.label(context, alert.packageName) }}") },
            text = { OutlinedTextField(replyText, { replyText = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("Reply text") }, maxLines = 4, enabled = canReply) },
            confirmButton = { TextButton(enabled = canReply && replyText.isNotBlank(), onClick = {
                val sent = NotificationReplyRegistry.reply(context, alert.packageName, alert.postedAt, replyText)
                if (!sent) CrashLog.record("NOTIFICATIONS", "banner reply action expired or unavailable")
                replyTarget = null
            }) { Text("Send reply") } },
            dismissButton = { TextButton(onClick = { replyTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NotificationBanner(
    alert: NotificationAlert,
    context: Context,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onReadAloud: () -> Unit
) {
    val appName = remember(alert.packageName) { PackageLabelResolver.label(context, alert.packageName) }
    val icon = remember(alert.packageName) { PackageLabelResolver.icon(context, alert.packageName)?.toBitmap(48, 48)?.asImageBitmap() }
    val canReply = remember(alert.packageName, alert.postedAt) { NotificationReplyRegistry.canReply(alert.packageName, alert.postedAt) }
    var showText by rememberSaveable(alert.id) { mutableStateOf(false) }
    val privateCategory = alert.category == "Calls" || alert.category == "Messages/SMS" || alert.category == "Messages (SMS)"
    val preview = if (privateCategory && showText) "${alert.title.ifBlank { appName }}: ${alert.text}" else safePreview(context, alert)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF242538))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Image(icon, contentDescription = "$appName app icon", modifier = Modifier.size(32.dp))
                else Icon(Icons.Filled.Notifications, contentDescription = "$appName notification", modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text("New · $appName", fontSize = 12.sp)
                    Text(alert.title.ifBlank { "Notification" }, fontSize = 14.sp)
                }
                Text(DateUtils.getRelativeTimeSpanString(alert.postedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), fontSize = 10.sp)
            }
            Text(preview, fontSize = 13.sp)
            if (privateCategory && alert.text.isNotBlank()) TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide text" else "Show text") }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage(alert.packageName)
                    if (launch != null) runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { CrashLog.record("NOTIFICATIONS", "banner app open failed", it) }
                }) { Text("Open app") }
                if (canReply) TextButton(onClick = onReply) { Text("Reply") }
                TextButton(onClick = onReadAloud) { Text("Read aloud") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun safePreview(context: Context, alert: NotificationAlert): String {
    val sender = alert.title.ifBlank { PackageLabelResolver.label(context, alert.packageName) }
    val maskedText = NotificationPrivacyFormatter.maskOtpCodes(alert.text)
    return NotificationPrivacyFormatter.line(alert.category, sender, maskedText).ifBlank { "New notification" }
}
