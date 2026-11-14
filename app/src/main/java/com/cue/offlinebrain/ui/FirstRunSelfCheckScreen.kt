package com.cue.offlinebrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.cue.offlinebrain.ui.BrainViewModel

private val CheckMuted = Color(0xFFAAA9B6)
private val CheckSurface = Color(0xFF1A1B25)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunSelfCheckScreen(
    vm: BrainViewModel,
    onTryInput: () -> Unit,
    onPermissions: () -> Unit,
    onContinue: () -> Unit
) {
    val message by vm.toast.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Color(0xFF101118),
        topBar = { CenterAlignedTopAppBar(title = { Text("Start here") }, navigationIcon = {
            IconButton(onClick = onContinue) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Skip tour and open Chat") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101118))) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CheckCard("Welcome · a short tour you can skip", "Chat is where you type or speak. Alarms make reminders; Notifications saves new alerts only after you turn on access. Tools contains phrase rules and macros, plus an optional floating shortcut that you start and stop yourself.") { }
            Text("You can try the checks below or skip straight to Chat. Permissions are never switched on for you.", color = CheckMuted, fontSize = 14.sp, lineHeight = 20.sp)
            CheckCard("1 · Text input", "The chat composer is fixed at the bottom of Chat and should stay above the keyboard. Open Chat and type a short test request.") {
                Button(onClick = onTryInput, modifier = Modifier.fillMaxWidth()) { Text("Open Chat and try typing") }
            }
            CheckCard("2 · Alarm", "Schedule a test alarm for about 30 seconds from now. Keep this app installed; use Stop on the alarm notification after it rings.") {
                Button(onClick = { vm.scheduleTestAlarmIn30Seconds() }, modifier = Modifier.fillMaxWidth()) { Text("Schedule test alarm") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            CheckCard("3 · Optional permissions", "Notification access and Accessibility are off until you choose to enable them. Review each permission in Settings, then run Diagnostics.") {
                Button(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("Review permissions") }
            }
            Spacer(Modifier.height(2.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Skip tour and open Chat") }
        }
    }
}

@Composable
private fun CheckCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CheckSurface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(5.dp))
            Text(description, color = CheckMuted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
