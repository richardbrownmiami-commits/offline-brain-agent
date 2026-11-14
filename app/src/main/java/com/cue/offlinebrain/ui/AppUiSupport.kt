package com.cue.offlinebrain

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val AppBackground = Color(0xFF101118)
internal val SurfaceDark = Color(0xFF1A1B25)
internal val Muted = Color(0xFFAAA9B6)
internal val Teal = Color(0xFF8FE3D8)

@Composable
internal fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(spring()), colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp)); content() }
    }
}
