package com.cue.offlinebrain.device

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.cue.offlinebrain.diagnostics.CrashLog
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object DeviceStatusProvider {
    enum class Request {
        SUMMARY, STORAGE, RAM, BATTERY, CHARGING, NETWORK, WIFI_NAME, MOBILE_DATA, BLUETOOTH,
        VOLUME, VOLUME_UP, VOLUME_DOWN, BRIGHTNESS, BRIGHTNESS_SETTINGS, PHONE_MODEL, ANDROID_VERSION, UPTIME,
        TEMPERATURE, INSTALLED_APPS, BIGGEST_APPS, NETWORK_OPERATOR, IP_ADDRESS,
        FLASHLIGHT_ON, FLASHLIGHT_OFF, DO_NOT_DISTURB
    }

    fun classify(text: String): Request? {
        val query = normalize(text)
        if (query.isBlank()) return null
        if (query.contains("flashlight") || query.contains("torch")) {
            if (query.contains(" on") || query == "flashlight on" || query == "torch on") return Request.FLASHLIGHT_ON
            if (query.contains(" off") || query == "flashlight off" || query == "torch off") return Request.FLASHLIGHT_OFF
        }
        if (query.contains("do not disturb") || query == "dnd") return Request.DO_NOT_DISTURB
        if (query.contains("biggest app") || query.contains("largest app") || query.contains("largest apps")) return Request.BIGGEST_APPS
        if (query.contains("installed app") || query.contains("apps count") || query.contains("number of apps")) return Request.INSTALLED_APPS
        if (query.contains("network operator") || query.contains("carrier name") || query.contains("mobile operator")) return Request.NETWORK_OPERATOR
        if (query.contains("ip address") || query == "ip") return Request.IP_ADDRESS
        if (query.contains("wifi") || query.contains("wi-fi") || query.contains("wi fi") || query.contains("wireless network")) return Request.WIFI_NAME
        if (query.contains("mobile data") || query.contains("cellular data")) return Request.MOBILE_DATA
        if (query.contains("bluetooth")) return Request.BLUETOOTH
        if (query.contains("brightness")) {
            if (listOf("up", "down", "increase", "decrease", "brighter", "dimmer", "change", "set").any(query::contains)) return Request.BRIGHTNESS_SETTINGS
            return Request.BRIGHTNESS
        }
        if (query.contains("volume") && (query.contains("up") || query.contains("increase"))) return Request.VOLUME_UP
        if (query.contains("volume") && (query.contains("down") || query.contains("decrease"))) return Request.VOLUME_DOWN
        if (query.contains("volume") || query.contains("sound level")) return Request.VOLUME
        if (query.contains("temperature") || query.contains("phone hot")) return Request.TEMPERATURE
        if (query.contains("uptime") || query.contains("how long has") || query.contains("how long is my phone on")) return Request.UPTIME
        if (query.contains("android version") || query.contains("which android")) return Request.ANDROID_VERSION
        if (query.contains("phone model") || query.contains("device model")) return Request.PHONE_MODEL
        if (query.contains("charging") || query.contains("plugged in")) return Request.CHARGING
        if (query.contains("battery") || query.contains("charge level")) return Request.BATTERY
        if (query.contains("ram") || query.contains("memory free") || query.contains("free memory")) return Request.RAM
        if (query.contains("storage") || query.contains("disk space") || query.contains("space left")) return Request.STORAGE
        if (query.contains("online") || query.contains("internet") || query.contains("network status") || query.contains("connected")) return Request.NETWORK
        if (query.contains("device status") || query.contains("phone status") || query == "status") return Request.SUMMARY
        return null
    }

    fun needsCameraPermission(text: String): Boolean = classify(text) in setOf(Request.FLASHLIGHT_ON, Request.FLASHLIGHT_OFF)
    fun needsPhoneStatePermission(text: String): Boolean = classify(text) == Request.NETWORK_OPERATOR

    fun reply(context: Context, request: Request, original: String = ""): String {
        return try {
            when (request) {
                Request.SUMMARY -> summary(context)
                Request.STORAGE -> storage(context)
                Request.RAM -> ram(context)
                Request.BATTERY -> battery(context)
                Request.CHARGING -> charging(context)
                Request.NETWORK -> network(context)
                Request.WIFI_NAME -> openSettings(context, Settings.ACTION_WIFI_SETTINGS, "Android hides the Wi-Fi name from apps unless location access is allowed. To avoid reading your location, I opened Wi-Fi settings so you can see the network there.")
                Request.MOBILE_DATA -> openSettings(context, Settings.ACTION_WIRELESS_SETTINGS, "Android 11 keeps the mobile-data switch under your control. I opened Wireless settings; I did not change the switch.")
                Request.BLUETOOTH -> openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Android 11 keeps Bluetooth changes under your control. I opened Bluetooth settings; I did not change the switch.")
                Request.VOLUME -> volume(context)
                Request.VOLUME_UP -> changeVolume(context, 1)
                Request.VOLUME_DOWN -> changeVolume(context, -1)
                Request.BRIGHTNESS -> brightness(context)
                Request.BRIGHTNESS_SETTINGS -> openSettings(context, Settings.ACTION_DISPLAY_SETTINGS, "Android controls screen brightness in Display settings. I opened that page; Cue did not change the brightness.")
                Request.PHONE_MODEL -> "Phone model: ${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim()
                Request.ANDROID_VERSION -> "Android ${Build.VERSION.RELEASE ?: "unknown"} (API ${Build.VERSION.SDK_INT})."
                Request.UPTIME -> uptime()
                Request.TEMPERATURE -> temperature(context)
                Request.INSTALLED_APPS -> installedApps(context)
                Request.BIGGEST_APPS -> openSettings(context, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "Android does not let Cue read other apps' storage sizes without extra access. I opened Android's app-management page so you can review app storage there.")
                Request.NETWORK_OPERATOR -> networkOperator(context)
                Request.IP_ADDRESS -> ipAddress()
                Request.FLASHLIGHT_ON -> flashlight(context, true)
                Request.FLASHLIGHT_OFF -> flashlight(context, false)
                Request.DO_NOT_DISTURB -> openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Android controls Do Not Disturb access in Settings. I opened that page; I did not change the setting.")
            }
        } catch (error: Exception) {
            CrashLog.record("DEVICE_STATUS", "device-status request failed (${request.name})", error)
            "I couldn't read or change that setting: ${error.message ?: error.javaClass.simpleName}. Nothing else was changed."
        }
    }

    /** Applies only the explicitly requested media-stream operation; no ringtone/alarm stream is touched. */
    fun applyVolumeCommand(context: Context, command: VolumeCommand): String = try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Media volume controls are unavailable."
        val stream = AudioManager.STREAM_MUSIC
        val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        val maximum = audio.getStreamMaxVolume(stream)
        val current = audio.getStreamVolume(stream)
        if (command == VolumeCommand.Read) {
            val percent = VolumeLevelPolicy.percentage(current, minimum, maximum)
            return if (percent == null) "Media volume: $current of $maximum." else "Media volume: $current of $maximum ($percent%)."
        }
        val target = VolumeLevelPolicy.target(current, minimum, maximum, command)
            ?: return "I couldn't safely change the media volume. Nothing was changed."
        audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        val updated = audio.getStreamVolume(stream)
        val percent = VolumeLevelPolicy.percentage(updated, minimum, maximum)
        if (percent == null) "Media volume is now $updated of $maximum."
        else "Media volume is now $updated of $maximum ($percent%)."
    } catch (error: Exception) {
        CrashLog.record("DEVICE_STATUS", "media volume command failed", error)
        "I couldn't change the media volume: ${error.message ?: error.javaClass.simpleName}. Nothing else was changed."
    }

    private fun summary(context: Context): String = buildString {
        append("Device status\n")
        append("Storage free: ").append(storage(context).removePrefix("Free internal storage: ")).append('\n')
        append("RAM free: ").append(ram(context).removePrefix("Available RAM: ")).append('\n')
        append("Battery: ").append(battery(context).removePrefix("Battery: ")).append('\n')
        append("Network: ").append(network(context).removePrefix("Network: ")).append('\n')
        append("Media volume: ").append(volume(context).removePrefix("Media volume: "))
    }

    private fun storage(context: Context): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        return "Free internal storage: ${formatBytes(stat.availableBytes)} of ${formatBytes(stat.totalBytes)}."
    }

    private fun ram(context: Context): String {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return "Android did not provide memory information."
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return "Available RAM: ${formatBytes(info.availMem)} of ${formatBytes(info.totalMem)}."
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery information is unavailable right now."
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100).toString() + "%" else "unknown"
        return "Battery: $percent, ${chargingLabel(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))}."
    }

    private fun charging(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Android did not provide charging information."
        val label = chargingLabel(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
        return if (label == "charging" || label == "full") "Yes, the phone is $label."
        else if (label == "not charging") "No, the phone is not charging."
        else "Android could not tell whether the phone is charging."
    }

    private fun chargingLabel(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "charging status unknown"
    }

    private fun network(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Network state is unavailable."
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!connected) return "Network: offline."
        val kind = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobile data"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "connected"
        }
        return "Network: online over $kind."
    }

    private fun volume(context: Context): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Media volume is unavailable."
        return "Media volume: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}."
    }

    private fun changeVolume(context: Context, direction: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Media volume controls are unavailable."
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return "Media volume changed. ${volume(context)}"
    }

    private fun brightness(context: Context): String {
        val level = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        if (level < 0) return "Brightness is controlled by Android, but its current level is unavailable."
        val percent = (level * 100 / 255).coerceIn(0, 100)
        return "Screen brightness: $percent% (0–100 scale)."
    }

    private fun uptime(): String {
        val seconds = (SystemClock.elapsedRealtime() / 1_000L).coerceAtLeast(0L)
        val days = seconds / 86_400L
        val hours = (seconds % 86_400L) / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        return if (days > 0) "Phone uptime: $days day(s), $hours hour(s), $minutes minute(s)."
        else "Phone uptime: $hours hour(s), $minutes minute(s)."
    }

    private fun temperature(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery temperature is unavailable right now."
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return "Battery temperature is not reported by Android."
        return "Battery temperature: ${"%.1f".format(Locale.US, tenths / 10.0)} °C. This is the battery sensor, not the room temperature."
    }

    private fun installedApps(context: Context): String = try {
        val count = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).size
        "Android reports $count visible installed app(s). Android 11 may hide apps that Cue is not allowed to query."
    } catch (error: Exception) {
        CrashLog.record("DEVICE_STATUS", "visible installed-app count failed", error)
        "Android did not allow Cue to count visible installed apps."
    }

    private fun networkOperator(context: Context): String {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "Android protects the mobile-network operator name with Phone permission. Cue did not request it. Open Android Settings → Apps → Cue → Permissions if you want to allow it."
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "Mobile-network information is unavailable."
        val name = manager.networkOperatorName.orEmpty().trim()
        return if (name.isBlank()) "The phone did not report a mobile-network operator." else "Mobile-network operator: ${name.take(100)}."
    }

    private fun ipAddress(): String = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
        val addresses = interfaces
            .filterNot { it.isLoopback }
            .flatMap { network -> Collections.list(network.inetAddresses).filterNot { it.isLoopbackAddress || it.isLinkLocalAddress } }
            .mapNotNull { it.hostAddress?.take(80) }
            .distinct().take(4)
        if (addresses.isEmpty()) "No local network IP address was found."
        else "Local IP address(es): ${addresses.joinToString(", ")}. This is a private network address, not your public internet address."
    } catch (error: Exception) {
        CrashLog.record("DEVICE_STATUS", "local IP address lookup failed", error)
        "Android could not show a local IP address."
    }

    private fun flashlight(context: Context, enabled: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return "Camera permission is needed to control the flashlight. Allow Cue's Camera permission in Android Settings, then try again."
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "This phone does not provide flashlight controls."
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone does not report a built-in flashlight."
        manager.setTorchMode(cameraId, enabled)
        return if (enabled) "Flashlight turned on." else "Flashlight turned off."
    }

    private fun openSettings(context: Context, action: String, message: String): String {
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            message
        } catch (error: Exception) {
            CrashLog.record("DEVICE_STATUS", "opening Android settings action failed ($action)", error)
            "Android could not open that Settings page. Open Settings and search for the requested control."
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "unknown"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> "${"%.1f".format(Locale.US, bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace('–', '-').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
