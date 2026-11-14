package com.cue.offlinebrain.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.cue.offlinebrain.diagnostics.CrashLog
import java.util.Locale

/** A tiny, refresh-on-demand snapshot for the visual Device Status card. */
data class DeviceMetric(
    val key: String,
    val label: String,
    val value: String,
    val progress: Float?,
    val settingsAction: String?,
    val warning: Boolean = false,
    val note: String = ""
)

object DeviceStatusSnapshotProvider {
    fun read(context: Context): List<DeviceMetric> = listOf(
        metric("storage", "Storage", { storage() }, "android.settings.INTERNAL_STORAGE_SETTINGS"),
        metric("ram", "RAM", { ram(context) }, "android.settings.APPLICATION_SETTINGS"),
        metric("battery", "Battery", { battery(context) }, "android.settings.BATTERY_SAVER_SETTINGS"),
        metric("network", "Network", { network(context) }, "android.settings.WIRELESS_SETTINGS"),
        metric("volume", "Media volume", { volume(context) }, "android.settings.SOUND_SETTINGS")
    )

    private fun metric(key: String, label: String, read: () -> DeviceMetric, action: String): DeviceMetric = try {
        read().copy(key = key, label = label, settingsAction = action)
    } catch (error: Exception) {
        CrashLog.record("DEVICE_STATUS", "status card metric unavailable: $key", error)
        DeviceMetric(key, label, "Unavailable right now", null, action)
    }

    private fun storage(): DeviceMetric {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes.coerceAtLeast(0L)
        val free = stat.availableBytes.coerceAtLeast(0L).coerceAtMost(total)
        val used = (total - free).coerceAtLeast(0L)
        val progress = if (total > 0L) used.toFloat() / total.toFloat() else null
        return DeviceMetric("", "", "${bytes(free)} free of ${bytes(total)}", progress, null)
    }

    private fun ram(context: Context): DeviceMetric {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return DeviceMetric("", "", "Unavailable right now", null, null)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val total = info.totalMem.coerceAtLeast(0L)
        val free = info.availMem.coerceAtLeast(0L).coerceAtMost(total)
        val used = (total - free).coerceAtLeast(0L)
        val progress = if (total > 0L) used.toFloat() / total.toFloat() else null
        return DeviceMetric("", "", "${bytes(free)} available of ${bytes(total)}", progress, null)
    }

    private fun battery(context: Context): DeviceMetric {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return DeviceMetric("", "", "Unavailable right now", null, null)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return DeviceMetric("", "", "Charge level unavailable", null, null)
        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            else -> "Not charging"
        }
        return DeviceMetric(
            "", "", "$percent% · $status", percent / 100f, null,
            warning = percent < 15,
            note = if (percent < 15) "Low battery may pause background capture on MIUI. Plug in the phone or review MIUI battery settings." else ""
        )
    }

    private fun network(context: Context): DeviceMetric {
        val status = DeviceStatusProvider.reply(context, DeviceStatusProvider.Request.NETWORK)
        val connected = status.contains("online", ignoreCase = true)
        val offline = status.contains("offline", ignoreCase = true)
        return DeviceMetric(
            "", "", status, progress = when { connected -> 1f; offline -> 0f; else -> null },
            settingsAction = null, warning = offline,
            note = if (offline) "No internet connection is reported right now." else ""
        )
    }

    private fun volume(context: Context): DeviceMetric {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DeviceMetric("", "", "Unavailable right now", null, null)
        val stream = AudioManager.STREAM_MUSIC
        val current = audio.getStreamVolume(stream)
        val minimum = if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        val maximum = audio.getStreamMaxVolume(stream)
        val fraction = if (maximum > minimum) (current - minimum).toFloat() / (maximum - minimum).toFloat() else null
        return DeviceMetric("", "", "$current of $maximum", fraction?.coerceIn(0f, 1f), null)
    }

    private fun bytes(value: Long): String = when {
        value < 0L -> "unknown"
        value < 1024L * 1024L -> "${value / 1024L} KB"
        value < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}
