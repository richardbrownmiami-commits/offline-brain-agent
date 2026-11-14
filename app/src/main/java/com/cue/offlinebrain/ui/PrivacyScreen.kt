package com.cue.offlinebrain.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavHostController
import com.cue.offlinebrain.data.AppSettings
import com.cue.offlinebrain.data.AutomationRuleEntity
import com.cue.offlinebrain.data.ClipboardEntryEntity
import com.cue.offlinebrain.data.MacroEntity
import com.cue.offlinebrain.data.MacroStepDraft
import com.cue.offlinebrain.data.MacroStepEditorModel
import com.cue.offlinebrain.data.MacroStepKind
import com.cue.offlinebrain.data.TypedMacroStep
import com.cue.offlinebrain.data.TypedMacroSteps
import com.cue.offlinebrain.data.PhraseChainParser
import com.cue.offlinebrain.data.NotificationEntity
import com.cue.offlinebrain.data.PinLockStore
import com.cue.offlinebrain.data.shouldStoreNotification
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.notifications.NotificationReplyRegistry
import com.cue.offlinebrain.notifications.NotificationAccessState
import com.cue.offlinebrain.notifications.NotificationCategory
import com.cue.offlinebrain.notifications.NotificationDecisionReason
import com.cue.offlinebrain.notifications.NotificationPipelineTelemetry
import com.cue.offlinebrain.notifications.NotificationTestCapture
import com.cue.offlinebrain.notifications.NotificationPrivacyFormatter
import com.cue.offlinebrain.notifications.PackageLabelResolver
import com.cue.offlinebrain.voice.VoiceSpeechOutput
import com.cue.offlinebrain.ui.BrainViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun PrivacyScreen(feature: SecurityViewModel, settings: AppSettings, onBack: () -> Unit, section: String = "all") {
    val vm = feature.coordinator
    val context = LocalContext.current
    val toast by vm.toast.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var showBackupPass by rememberSaveable { mutableStateOf(false) }
    var restorePass by remember { mutableStateOf("") }
    var showRestorePass by rememberSaveable { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupStep by rememberSaveable { mutableStateOf(1) }
    var backupPreview by remember { mutableStateOf<com.cue.offlinebrain.data.EncryptedBackupPreview?>(null) }
    var backupPreviewError by remember { mutableStateOf<String?>(null) }
    var backupShareUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var savedBackupName by rememberSaveable { mutableStateOf("") }
    var lastBackupAt by rememberSaveable { mutableStateOf(context.getSharedPreferences("backup_status", Context.MODE_PRIVATE).getLong("last_portable_backup_at", 0L)) }
    var lastBackupBytes by rememberSaveable { mutableStateOf(context.getSharedPreferences("backup_status", Context.MODE_PRIVATE).getLong("last_portable_backup_bytes", 0L)) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var wipeText by rememberSaveable { mutableStateOf("") }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var existingPin by rememberSaveable { mutableStateOf("") }
    var removePin by rememberSaveable { mutableStateOf("") }
    var currentPin by rememberSaveable { mutableStateOf("") }
    var replacementPin by rememberSaveable { mutableStateOf("") }
    var replacementConfirm by rememberSaveable { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var showLockDelayMenu by remember { mutableStateOf(false) }
    val securityWorking by vm.securityWorking.collectAsStateWithLifecycle()
    val hasSavedPin = remember(settings.appLockEnabled) { PinLockStore.hasPin(context) || settings.appLockEnabled }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            vm.setToast("Saving the encrypted backup to the location you chose…")
            vm.exportEncryptedBackup(uri, backupPass) { success, size ->
                if (success) {
                    backupShareUri = uri
                    savedBackupName = try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column).orEmpty() else ""
                        }.orEmpty().ifBlank { "the file you chose" }
                    } catch (error: Exception) {
                        CrashLog.record("BACKUP", "saved backup display name could not be read", error)
                        "the file you chose"
                    }
                    lastBackupAt = System.currentTimeMillis()
                    lastBackupBytes = size ?: 0L
                    backupStep = 3
                    backupPass = ""; backupConfirm = ""
                }
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selectedRestoreUri = uri; backupPreview = null; backupPreviewError = null; restorePass = "" }
    }
    FeatureScaffold(if (section == "backup") "Backup and restore" else if (section == "security") "Privacy and security" else "Privacy & backups", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section != "backup") FeatureCard("Optional app lock") {
                Text("Use a 6–12 digit app PIN. PIN setup finishes saving before the app reports success. Android fingerprint or device-screen credentials are offered at the lock screen when supported. If you forget the PIN and your device credential is unavailable, the app cannot recover it.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (settings.appLockEnabled) {
                    Text("App lock is enabled. The PIN verifier stays saved on this device.", color = MaterialTheme.colorScheme.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hide app preview in Recents")
                            Text("Also blocks screenshots while this app is open.", color = MutedText, fontSize = 12.sp)
                        }
                        Switch(checked = settings.hideRecentsPreview, onCheckedChange = vm::setHideRecentsPreview)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lock after leaving the app", Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { showLockDelayMenu = true }) {
                                Text(when (settings.appLockTimeoutSeconds) { 0 -> "Immediately"; 30 -> "30 seconds"; 60 -> "1 minute"; 120 -> "2 minutes"; 300 -> "5 minutes"; 900 -> "15 minutes"; else -> "2 minutes" })
                            }
                            DropdownMenu(expanded = showLockDelayMenu, onDismissRequest = { showLockDelayMenu = false }) {
                                listOf(0 to "Immediately", 30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes", 900 to "15 minutes").forEach { (seconds, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { vm.setAppLockTimeoutSeconds(seconds); showLockDelayMenu = false })
                                }
                            }
                        }
                    }
                    Text("A short visit to Recents or another app does not lock the screen. The timer starts when this app actually leaves the foreground; a cold start always asks for your PIN.", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                    var showDisable by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { showDisable = true; removePin = "" }, modifier = Modifier.fillMaxWidth()) { Text("Remove app lock") }
                    if (showDisable) AlertDialog(
                        onDismissRequest = { if (!securityWorking) showDisable = false },
                        title = { Text("Remove app lock?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Enter your current PIN to turn the lock off. The saved PIN stays on this device, so you can turn the lock back on without creating another one.")
                                OutlinedTextField(removePin, { removePin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Current PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = !securityWorking && removePin.length in 6..12, onClick = {
                                vm.disableAppLock(removePin) { disabled ->
                                    if (disabled) { showDisable = false; removePin = ""; pinError = null }
                                    else pinError = "The PIN did not match or checks are temporarily paused. The lock is still on."
                                }
                            }) { Text(if (securityWorking) "Checking…" else "Remove lock") }
                        },
                        dismissButton = { TextButton(onClick = { showDisable = false; removePin = "" }) { Text("Cancel") } }
                    )
                } else if (hasSavedPin) {
                    Text("A PIN is already saved. Enter it to enable the app lock; you do not need to create another PIN.", color = MutedText, fontSize = 13.sp)
                    OutlinedTextField(existingPin, { existingPin = it.filter(Char::isDigit).take(12); pinError = null }, Modifier.fillMaxWidth(), label = { Text("Saved app PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = {
                        vm.enableAppLockWithExistingPin(existingPin) { verified ->
                            if (verified) { existingPin = ""; pinError = null }
                            else pinError = "That PIN did not match, or attempts are temporarily paused. Wait briefly or use your device screen lock."
                        }
                    }, enabled = !securityWorking && existingPin.length in 6..12, modifier = Modifier.fillMaxWidth()) { Text(if (securityWorking) "Checking PIN…" else "Enable app lock") }
                } else {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Confirm PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = { vm.saveAppPin(pin, confirmPin); pin = ""; confirmPin = "" }, enabled = !securityWorking, modifier = Modifier.fillMaxWidth()) { Text(if (securityWorking) "Saving PIN securely…" else "Set PIN and enable app lock") }
                }
                if (hasSavedPin) {
                    Text("Change saved PIN", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(currentPin, { currentPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Current PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(replacementPin, { replacementPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(replacementConfirm, { replacementConfirm = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Confirm new PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = { vm.changeAppPin(currentPin, replacementPin, replacementConfirm); currentPin = ""; replacementPin = ""; replacementConfirm = "" }, enabled = !securityWorking, modifier = Modifier.fillMaxWidth()) { Text(if (securityWorking) "Saving PIN securely…" else "Change PIN") }
                }
                pinError?.let { Text(it, color = Color(0xFFFF8C94), fontSize = 13.sp) }
            }
            if (section == "all") FeatureCard("Background refresh") {
                Text("When enabled, weather, headlines and an optional saved custom API are refreshed about every 12 hours. WorkManager requires network and a battery that is not low; Android may defer the run. Custom API headers are sent only to the URL you saved.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.backgroundRefreshEnabled) "Enabled" else "Off", Modifier.weight(1f)); Switch(settings.backgroundRefreshEnabled, { vm.setBackgroundRefreshEnabled(it) }) }
            }
            if (section != "security") FeatureCard("Daily encrypted backup on this device") {
                Text("Off by default. When enabled, the app keeps an encrypted backup in private storage on this phone. It does not upload the file. Use the portable backup below to move it to another device.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (settings.autoBackupEnabled) "Enabled" else "Off", Modifier.weight(1f)); Switch(settings.autoBackupEnabled, { vm.setAutoBackupEnabled(it) }) }
            }
            if (section != "security") FeatureCard("How backup works") {
                Text("A portable backup is locked with your passphrase and saved only to the folder you choose. It is not uploaded. You need the same passphrase to restore it; if you forget it, nobody can recover the file. The optional daily backup stays on this phone and is off by default.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (section != "security") FeatureCard("Portable encrypted backup") {
                Text("The file contains chats, alarms, notes, saved notifications, rules, macros, clipboard history, learned phrases and portable settings. Saved custom API headers are included only inside the encrypted file. Your app PIN and Android permissions stay on this phone. The passphrase is never saved by the app.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                if (lastBackupAt > 0L) {
                    val sizeLabel = if (lastBackupBytes < 1024L) "$lastBackupBytes bytes" else "${lastBackupBytes / 1024L} KB"
                    Text("Last portable backup: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastBackupAt))} · $sizeLabel", color = MutedText, fontSize = 12.sp)
                } else Text("Last portable backup: none recorded on this phone yet.", color = MutedText, fontSize = 12.sp)

                if (backupStep == 1) {
                    Text("Create a backup", fontWeight = FontWeight.SemiBold)
                    Text("Choose a long passphrase (at least 10 characters). You will need the same passphrase to restore the file.", color = MutedText, fontSize = 12.sp)
                    Button(onClick = { backupStep = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Start backup") }
                } else if (backupStep == 2) {
                    Text("Step 1 of 2 · Set a passphrase", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(backupPass, { backupPass = it }, Modifier.fillMaxWidth(), label = { Text("Backup passphrase") }, visualTransformation = if (showBackupPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(backupConfirm, { backupConfirm = it }, Modifier.fillMaxWidth(), label = { Text("Type it again") }, visualTransformation = if (showBackupPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    TextButton(onClick = { showBackupPass = !showBackupPass }) { Text(if (showBackupPass) "Hide passphrase" else "Show passphrase") }
                    Text(when {
                        backupPass.length < 10 -> "Use at least 10 characters. A longer, memorable phrase is safer."
                        backupPass != backupConfirm -> "The two passphrases do not match yet."
                        else -> "Passphrase confirmed. It will not be stored by the app."
                    }, color = MutedText, fontSize = 12.sp)
                    Button(onClick = { createBackup.launch("cue-backup.cueb") }, enabled = backupPass.length >= 10 && backupPass == backupConfirm, modifier = Modifier.fillMaxWidth()) { Text("Choose where to save") }
                    TextButton(onClick = { backupStep = 1; backupPass = ""; backupConfirm = "" }) { Text("Cancel") }
                } else {
                    Text("Backup saved", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                    Text("Saved as $savedBackupName. The file is encrypted. Keep your passphrase somewhere safe; Cue cannot recover it.", color = MutedText, fontSize = 12.sp)
                    if (backupShareUri != null) Button(onClick = {
                        try {
                            val uri = backupShareUri ?: return@Button
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/octet-stream")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            intent.clipData = android.content.ClipData.newUri(context.contentResolver, "Encrypted backup", uri)
                            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(intent, "Share encrypted backup"))
                        } catch (error: Exception) {
                            CrashLog.record("BACKUP", "backup share chooser failed", error)
                            vm.setToast("Android could not open the share menu.")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Share backup file") }
                    TextButton(onClick = { backupStep = 1 }) { Text("Create another backup") }
                }

                androidx.compose.material3.HorizontalDivider()
                Text("Restore a backup", fontWeight = FontWeight.SemiBold)
                Text("Choose a file and enter its passphrase. The preview checks the encrypted file and shows its contents before you replace anything.", color = MutedText, fontSize = 12.sp)
                OutlinedButton(onClick = { openBackup.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (selectedRestoreUri == null) "Choose backup file" else "Choose a different file") }
                selectedRestoreUri?.let { uri ->
                    Text("File selected. It has not been restored.", color = MutedText, fontSize = 12.sp)
                    OutlinedTextField(restorePass, { restorePass = it; backupPreview = null; backupPreviewError = null }, Modifier.fillMaxWidth(), label = { Text("Backup passphrase") }, visualTransformation = if (showRestorePass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    TextButton(onClick = { showRestorePass = !showRestorePass }) { Text(if (showRestorePass) "Hide passphrase" else "Show passphrase") }
                    Button(onClick = { backupPreview = null; backupPreviewError = "Checking the encrypted file…"; vm.previewEncryptedBackup(uri, restorePass) { result, error -> backupPreview = result; backupPreviewError = error } }, enabled = restorePass.length >= 10 && backupPreviewError != "Checking the encrypted file…", modifier = Modifier.fillMaxWidth()) { Text(if (backupPreviewError == "Checking the encrypted file…") "Checking…" else "Check passphrase and preview") }
                    backupPreviewError?.let { Text(it, color = if (backupPreview == null && it.startsWith("This backup could not")) Color(0xFFFF8C94) else MutedText, fontSize = 12.sp) }
                    backupPreview?.let { preview ->
                        Text("Contents in this file", fontWeight = FontWeight.Medium)
                        Text("${preview.messages} chats · ${preview.alarms} alarms · ${preview.notes} notes · ${preview.notifications} saved notifications · ${preview.rules} rules · ${preview.macros} macros · ${preview.clipboardEntries} clipboard items · ${preview.phrases} learned phrases · ${preview.settings} settings", color = MutedText, fontSize = 12.sp, lineHeight = 17.sp)
                        Text("Records are replaced in a Room database transaction. Portable settings are restored separately afterward.", color = MutedText, fontSize = 12.sp)
                        OutlinedButton(onClick = { showRestoreConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Review replacement") }
                    }
                }
            }
            if (section != "backup") FeatureCard("Delete local user data") {
                Text("This removes chats, alarms, notes, saved notification and clipboard text, rules, macros, local caches, settings and private automatic backups. Built-in FAQ data and the encrypted database key remain so the app can open safely. Export first if you may need this data.", color = MutedText, fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedButton(onClick = { wipeText = ""; showWipeConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Wipe local user data") }
            }
            toast?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
        }
    }
    if (showRestoreConfirm) AlertDialog(
        onDismissRequest = { showRestoreConfirm = false },
        title = { Text("Replace this phone's records?") },
        text = {
            val preview = backupPreview
            Text(if (preview == null) "The verified preview is no longer available. Cancel and check the file again."
            else "This encrypted file contains ${preview.messages} chats, ${preview.alarms} alarms, ${preview.notes} notes, ${preview.notifications} saved notifications, ${preview.rules} rules, ${preview.macros} macros, ${preview.clipboardEntries} clipboard items and ${preview.phrases} learned phrases. Replacing records cannot be undone. Database records are replaced in one transaction; portable settings are restored afterward.")
        },
        confirmButton = { TextButton(enabled = backupPreview != null && selectedRestoreUri != null, onClick = {
            val uri = selectedRestoreUri
            if (uri != null) vm.restoreEncryptedBackup(uri, restorePass)
            showRestoreConfirm = false; selectedRestoreUri = null; backupPreview = null; backupPreviewError = null; restorePass = ""
        }) { Text("Restore and replace") } },
        dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
    )
    if (showWipeConfirm) AlertDialog(
        onDismissRequest = { showWipeConfirm = false },
        title = { Text("Wipe local user data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This permanently deletes local user records and settings on this device. Type WIPE exactly to enable the final button.")
                OutlinedTextField(wipeText, { wipeText = it.take(8) }, label = { Text("Type WIPE") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = wipeText == "WIPE", onClick = { vm.wipeAllUserData(); showWipeConfirm = false; wipeText = "" }) { Text("Wipe data") } },
        dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } }
    )
}
