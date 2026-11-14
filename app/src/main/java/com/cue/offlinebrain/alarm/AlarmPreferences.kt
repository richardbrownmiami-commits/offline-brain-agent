package com.cue.offlinebrain.alarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import java.util.Locale

/** Synchronous component-facing mirror of the settings UI values. */
object AlarmPreferences {
    private const val PREFS = "alarm_preferences"

    fun snoozeMinutes(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("snooze_minutes", 5).coerceIn(1, 60)
    }.getOrDefault(5)

    fun setSnoozeMinutes(context: Context, minutes: Int) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("snooze_minutes", minutes.coerceIn(1, 60)).apply() }
    }

    fun selectedSoundUri(context: Context): Uri? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sound_uri", "").orEmpty()
            .takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }.getOrNull()

    fun channelId(context: Context): String {
        val signature = selectedSoundUri(context)?.toString().orEmpty().hashCode()
        return "brain_alarms_v3_${String.format(Locale.ROOT, "%08x", signature)}"
    }
}
