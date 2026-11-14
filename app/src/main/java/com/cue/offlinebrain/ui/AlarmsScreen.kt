package com.cue.offlinebrain.ui

import com.cue.offlinebrain.AppBackground
import com.cue.offlinebrain.SurfaceDark
import com.cue.offlinebrain.Muted
import com.cue.offlinebrain.Teal
import com.cue.offlinebrain.SettingsCard

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.AppLockStateStore
import com.cue.offlinebrain.data.AppLockLifecycle
import com.cue.offlinebrain.data.AlarmEntity
import com.cue.offlinebrain.data.MessageEntity
import com.cue.offlinebrain.data.NoteEntity
import com.cue.offlinebrain.data.ClipboardEntryEntity
import com.cue.offlinebrain.data.ClipboardCapturePolicy
import com.cue.offlinebrain.summary.ResumeEvidenceMarkup
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.voice.SpeechDiagnostics
import com.cue.offlinebrain.voice.VoiceEngineMode
import com.cue.offlinebrain.device.DeviceStatusProvider
import com.cue.offlinebrain.diagnostics.DiagnosticResult
import com.cue.offlinebrain.ui.BrainViewModel
import com.cue.offlinebrain.ui.FirstRunSelfCheckScreen
import com.cue.offlinebrain.ui.AppLockScreen
import com.cue.offlinebrain.ui.AutomationScreen
import com.cue.offlinebrain.ui.ClipboardScreen
import com.cue.offlinebrain.ui.NotificationsScreen
import com.cue.offlinebrain.ui.PhraseTeachingScreen
import com.cue.offlinebrain.ui.PrivacyScreen
import com.cue.offlinebrain.ui.SettingsHomeScreen
import com.cue.offlinebrain.ui.ToolsHomeScreen
import com.cue.offlinebrain.ui.AccessibilityOffBanner
import com.cue.offlinebrain.ui.FloatingButtonSetupScreen
import com.cue.offlinebrain.ui.AppearanceSettingsScreen
import com.cue.offlinebrain.ui.VoiceSettingsScreen
import com.cue.offlinebrain.ui.OnlineSettingsScreen
import com.cue.offlinebrain.ui.PermissionsSettingsScreen
import com.cue.offlinebrain.ui.AboutHelpScreen
import com.cue.offlinebrain.overlay.FloatingAssistantOverlayService
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmsScreen(feature: AlarmViewModel, settings: AppSettings, onBack: () -> Unit) {
    val vm = feature.coordinator
    val context = LocalContext.current
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val notice by vm.toast.collectAsStateWithLifecycle()
    val hiddenAlarmIds = remember { mutableStateListOf<Long>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var snooze by remember(settings.snoozeMinutes) { mutableFloatStateOf(settings.snoozeMinutes.toFloat()) }
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var addLabel by rememberSaveable { mutableStateOf("Reminder") }
    var addWhen by rememberSaveable { mutableStateOf(System.currentTimeMillis() + 3_600_000L) }
    val repeatingAlarms = alarms.filter { it.cron != null && it.id !in hiddenAlarmIds }
    var soundLabel by remember(settings.alarmSoundUri) { mutableStateOf(if (settings.alarmSoundUri.isBlank()) "System default" else "Custom alarm sound selected") }
    val editorLabel = remember(editing?.id) { mutableStateOf(editing?.label.orEmpty()) }
    val editorTime = remember(editing?.id) { mutableStateOf(editing?.triggerAt ?: System.currentTimeMillis()) }
    fun requestAlarmDelete(alarm: AlarmEntity) {
        if (alarm.id in hiddenAlarmIds) return
        hiddenAlarmIds.add(alarm.id)
        snackbarScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "${alarm.label} removed",
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) hiddenAlarmIds.remove(alarm.id)
            else vm.deleteAlarm(alarm.id) { deleted -> if (!deleted) hiddenAlarmIds.remove(alarm.id) }
        }
    }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            soundLabel = if (uri == null) "System default" else "Custom alarm sound selected"
            vm.setting { setAlarmSound(uri?.toString().orEmpty()) }
        }
    }
    Scaffold(
        containerColor = AppBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { CenterAlignedTopAppBar(title = { Text("Alarms") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("This page lists repeating alarms. One-time reminders are kept only until you stop them after they ring, then removed. Chat reminders use Android alarm-clock events.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Swipe a repeating alarm left or tap Delete to remove it. The Undo button keeps it if you change your mind.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Add alarm") }
                OutlinedButton(onClick = { vm.scheduleTestAlarmIn30Seconds() }, modifier = Modifier.fillMaxWidth()) { Text("Test alarm in 30 seconds") }
                notice?.let { Text(it, color = Teal, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            item {
                SettingsCard("Alarm options") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Also add one-time reminders to Clock", fontWeight = FontWeight.Medium)
                            Text("Off by default. When on, a one-time Chat reminder is also sent to your phone's Clock app. The in-app alarm and Clock alarm can both ring.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Switch(settings.mirrorAlarmsToSystemClock, { vm.setMirrorAlarmsToSystemClock(it) })
                    }
                    Text("Snooze: ${snooze.toInt()} minutes", color = Muted, fontSize = 13.sp)
                    Slider(value = snooze, onValueChange = { snooze = it }, valueRange = 1f..60f, steps = 58, onValueChangeFinished = { vm.setting { setSnoozeMinutes(snooze.toInt()) } })
                    Button(onClick = {
                        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose alarm sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.alarmSoundUri.takeIf(String::isNotBlank)?.let(Uri::parse))
                        }
                        runCatching { soundPicker.launch(picker) }.onFailure { vm.setToast("Alarm sound picker isn't available on this phone.") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Choose alarm sound · $soundLabel") }
                    Text("The alarm label is also the spoken announcement. Android may require you to allow alarm sounds and notifications.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            if (repeatingAlarms.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Text("No repeating alarms saved. One-time alarms and reminders do not stay in this list.", Modifier.padding(16.dp), color = Muted)
                    }
                }
            }
            items(repeatingAlarms, key = { it.id }) { alarm ->
                var dragOffset by remember(alarm.id) { mutableFloatStateOf(0f) }
                Card(
                    Modifier.fillMaxWidth().graphicsLayer { translationX = dragOffset }
                        .pointerInput(alarm.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (dragOffset < -84.dp.toPx()) requestAlarmDelete(alarm); dragOffset = 0f },
                                onDragCancel = { dragOffset = 0f },
                                onHorizontalDrag = { change, amount ->
                                    dragOffset = (dragOffset + amount).coerceIn(-280.dp.toPx(), 0f)
                                    change.consume()
                                }
                            )
                        }.animateContentSize(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(alarm.label, fontWeight = FontWeight.SemiBold)
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(alarm.triggerAt)), color = Muted, fontSize = 12.sp)
                                Text(repeatDescription(alarm.cron), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                            }
                            Switch(checked = alarm.enabled, onCheckedChange = { vm.setAlarmEnabled(alarm.id, it) })
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { editing = alarm }) { Text("Edit") }
                            TextButton(onClick = { requestAlarmDelete(alarm) }) { Text("Delete", color = Color(0xFFFF8C94)) }
                        }
                    }
                }
            }
        }
    }
    if (addDialog) AlertDialog(
        onDismissRequest = { addDialog = false },
        title = { Text("Add a one-time alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(addLabel, { addLabel = it }, Modifier.fillMaxWidth(), label = { Text("What should I remind you about?") }, singleLine = true)
                TextButton(onClick = {
                    val current = Calendar.getInstance().apply { timeInMillis = addWhen }
                    DatePickerDialog(context, { _, year, month, day ->
                        val chosen = Calendar.getInstance().apply { timeInMillis = addWhen; set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day) }
                        TimePickerDialog(context, { _, hour, minute ->
                            chosen.set(Calendar.HOUR_OF_DAY, hour); chosen.set(Calendar.MINUTE, minute); chosen.set(Calendar.SECOND, 0); chosen.set(Calendar.MILLISECOND, 0)
                            addWhen = chosen.timeInMillis
                        }, chosen.get(Calendar.HOUR_OF_DAY), chosen.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                    }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Time: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(addWhen))}") }
                Text("After it rings, tap Stop to remove it. The optional Clock setting may create a second alarm.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.addOneTimeAlarm(addLabel, addWhen); addDialog = false }) { Text("Set alarm") } },
        dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } }
    )
    editing?.let { alarm ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit alarm") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = editorLabel.value, onValueChange = { editorLabel.value = it }, label = { Text("Announcement text") }, singleLine = true)
                    TextButton(onClick = {
                        val current = Calendar.getInstance().apply { timeInMillis = editorTime.value }
                        DatePickerDialog(context, { _, year, month, day ->
                            val selected = Calendar.getInstance().apply {
                                timeInMillis = editorTime.value
                                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            }
                            TimePickerDialog(context, { _, hour, minute ->
                                selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute)
                                selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0)
                                editorTime.value = selected.timeInMillis
                            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("Time: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(editorTime.value))}") }
                    Text("Repeat: ${repeatDescription(alarm.cron)}", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.updateAlarm(alarm, editorLabel.value, editorTime.value); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

private fun repeatDescription(cron: String?): String {
    if (cron == null) return "One time"
    val days = cron.trim().split(Regex("\\s+")).getOrNull(4) ?: return "Repeats: $cron"
    return when (days) {
        "*" -> "Repeats every day"
        "1-5" -> "Repeats on weekdays"
        "0,6" -> "Repeats on weekends"
        "0" -> "Repeats every Sunday"
        "1" -> "Repeats every Monday"
        "2" -> "Repeats every Tuesday"
        "3" -> "Repeats every Wednesday"
        "4" -> "Repeats every Thursday"
        "5" -> "Repeats every Friday"
        "6" -> "Repeats every Saturday"
        else -> "Repeats weekly · $days"
    }
}
