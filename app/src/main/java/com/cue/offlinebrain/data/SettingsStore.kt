package com.cue.offlinebrain.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.voice.VoiceEngineMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.brainSettings by preferencesDataStore(name = "brain_settings")

data class LearnedPhrase(val phrase: String, val intent: String, val argument: String = "")

object LearnedPhraseMatcher {
    fun firstMatch(query: String, phrases: List<LearnedPhrase>): LearnedPhrase? =
        phrases.firstOrNull { it.phrase.isNotBlank() && query.contains(it.phrase, ignoreCase = true) }
}

object ClipboardCapturePolicy {
    fun normalize(text: String?): String? = text?.trim()?.take(4000)?.takeIf { it.isNotBlank() }
}

data class AppSettings(
    val accent: Int = 0xFFA78BFA.toInt(),
    val defaultCity: String = "New Delhi",
    val customPhrase: String = "",
    val newsFeed: String = "https://feeds.bbci.co.uk/news/world/rss.xml",
    val voiceReplies: Boolean = false,
    val fontScale: Int = 100,
    val customApiUrl: String = "",
    val customJsonPath: String = "",
    val customHeaders: String = "",
    val learnedPhrases: List<LearnedPhrase> = emptyList(),
    val lastIntent: String = "",
    val lastCity: String = "",
    val lastAlarmRequest: String = "",
    val snoozeMinutes: Int = 5,
    val alarmSoundUri: String = "",
    val useAdvancedVosk: Boolean = false,
    val voiceEngineMode: VoiceEngineMode = if (useAdvancedVosk) VoiceEngineMode.OFFLINE else VoiceEngineMode.AUTO,
    val autoSendVoiceTranscript: Boolean = false,
    val selfCheckSeen: Boolean = false,
    val notificationAllowlist: String = "",
    val notificationBlocklist: String = "",
    val notificationRetentionDays: Int = 30,
    val clipboardHistoryEnabled: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockTimeoutSeconds: Int = AppLockPolicy.DEFAULT_TIMEOUT_SECONDS,
    val hideRecentsPreview: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val backgroundRefreshEnabled: Boolean = false,
    val notificationCategoryOverrides: String = "",
    val notificationLiveCaptureEnabled: Boolean = false,
    val notificationCaptureConfigured: Boolean = false,
    val notificationKeepAliveEnabled: Boolean = false,
    val mirrorAlarmsToSystemClock: Boolean = false,
    val macroTutorialSeen: Boolean = false
)

fun AppSettings.shouldStoreNotification(packageName: String): Boolean {
    val name = packageName.trim().lowercase()
    val blocked = notificationBlocklist.lines().map { it.trim().lowercase() }.filter { it.isNotBlank() }
    if (blocked.any { name == it || name.startsWith("$it.") }) return false
    val allowed = notificationAllowlist.lines().map { it.trim().lowercase() }.filter { it.isNotBlank() }
    return allowed.isEmpty() || allowed.any { name == it || name.startsWith("$it.") }
}

class SettingsStore(private val context: Context) {
    internal val applicationContext: Context get() = context.applicationContext

    private object Keys {
        val accent = intPreferencesKey("accent")
        val city = stringPreferencesKey("default_city")
        val phrase = stringPreferencesKey("custom_phrase")
        val feed = stringPreferencesKey("news_feed")
        val voice = booleanPreferencesKey("voice_replies")
        val fontScale = intPreferencesKey("font_scale")
        val apiUrl = stringPreferencesKey("custom_api_url")
        val jsonPath = stringPreferencesKey("custom_json_path")
        val learnedPhrases = stringPreferencesKey("learned_phrases")
        val lastIntent = stringPreferencesKey("last_intent")
        val lastCity = stringPreferencesKey("last_city")
        val lastAlarmRequest = stringPreferencesKey("last_alarm_request")
        val snoozeMinutes = intPreferencesKey("snooze_minutes")
        val alarmSoundUri = stringPreferencesKey("alarm_sound_uri")
        val useAdvancedVosk = booleanPreferencesKey("use_advanced_vosk")
        val voiceEngineMode = stringPreferencesKey("voice_engine_mode")
        val autoSendVoiceTranscript = booleanPreferencesKey("auto_send_voice_transcript")
        val selfCheckSeen = booleanPreferencesKey("self_check_seen")
        val notificationAllowlist = stringPreferencesKey("notification_allowlist")
        val notificationBlocklist = stringPreferencesKey("notification_blocklist")
        val notificationRetentionDays = intPreferencesKey("notification_retention_days")
        val clipboardHistoryEnabled = booleanPreferencesKey("clipboard_history_enabled")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val appLockTimeoutSeconds = intPreferencesKey("app_lock_timeout_seconds")
        val hideRecentsPreview = booleanPreferencesKey("hide_recents_preview")
        val autoBackupEnabled = booleanPreferencesKey("auto_backup_enabled")
        val backgroundRefreshEnabled = booleanPreferencesKey("background_refresh_enabled")
        val notificationCategoryOverrides = stringPreferencesKey("notification_category_overrides")
        val notificationLiveCaptureEnabled = booleanPreferencesKey("notification_live_capture_enabled")
        val notificationKeepAliveEnabled = booleanPreferencesKey("notification_keep_alive_enabled")
        val mirrorAlarmsToSystemClock = booleanPreferencesKey("mirror_alarms_to_system_clock")
        val macroTutorialSeen = booleanPreferencesKey("macro_tutorial_seen")
    }

    val settings: Flow<AppSettings> = context.brainSettings.data.map { prefs ->
        val voiceMode = prefs[Keys.voiceEngineMode]?.let { runCatching { VoiceEngineMode.valueOf(it) }.getOrNull() }
            ?: if (prefs[Keys.useAdvancedVosk] == true) VoiceEngineMode.OFFLINE else VoiceEngineMode.AUTO
        AppSettings(
            accent = prefs[Keys.accent] ?: 0xFFA78BFA.toInt(),
            defaultCity = prefs[Keys.city] ?: "New Delhi",
            customPhrase = prefs[Keys.phrase] ?: "",
            newsFeed = prefs[Keys.feed] ?: "https://feeds.bbci.co.uk/news/world/rss.xml",
            voiceReplies = prefs[Keys.voice] ?: false,
            fontScale = prefs[Keys.fontScale] ?: 100,
            customApiUrl = prefs[Keys.apiUrl] ?: "",
            customJsonPath = prefs[Keys.jsonPath] ?: "",
            customHeaders = "",
            learnedPhrases = parsePhrases(prefs[Keys.learnedPhrases].orEmpty()),
            lastIntent = prefs[Keys.lastIntent].orEmpty(),
            lastCity = prefs[Keys.lastCity].orEmpty(),
            lastAlarmRequest = prefs[Keys.lastAlarmRequest].orEmpty(),
            snoozeMinutes = (prefs[Keys.snoozeMinutes] ?: 5).coerceIn(1, 60),
            alarmSoundUri = prefs[Keys.alarmSoundUri].orEmpty(),
            useAdvancedVosk = voiceMode == VoiceEngineMode.OFFLINE,
            voiceEngineMode = voiceMode,
            autoSendVoiceTranscript = prefs[Keys.autoSendVoiceTranscript] ?: false,
            selfCheckSeen = prefs[Keys.selfCheckSeen] ?: false,
            notificationAllowlist = prefs[Keys.notificationAllowlist].orEmpty(),
            notificationBlocklist = prefs[Keys.notificationBlocklist].orEmpty(),
            notificationRetentionDays = (prefs[Keys.notificationRetentionDays] ?: 30).coerceIn(1, 365),
            clipboardHistoryEnabled = prefs[Keys.clipboardHistoryEnabled] ?: false,
            appLockEnabled = prefs[Keys.appLockEnabled] ?: false,
            appLockTimeoutSeconds = AppLockPolicy.normalizeTimeoutSeconds(prefs[Keys.appLockTimeoutSeconds] ?: AppLockPolicy.DEFAULT_TIMEOUT_SECONDS),
            hideRecentsPreview = prefs[Keys.hideRecentsPreview] ?: false,
            autoBackupEnabled = prefs[Keys.autoBackupEnabled] ?: false,
            backgroundRefreshEnabled = prefs[Keys.backgroundRefreshEnabled] ?: false,
            notificationCategoryOverrides = prefs[Keys.notificationCategoryOverrides].orEmpty(),
            notificationLiveCaptureEnabled = prefs[Keys.notificationLiveCaptureEnabled] ?: false,
            notificationCaptureConfigured = prefs.contains(Keys.notificationLiveCaptureEnabled),
            notificationKeepAliveEnabled = prefs[Keys.notificationKeepAliveEnabled] ?: false,
            mirrorAlarmsToSystemClock = prefs[Keys.mirrorAlarmsToSystemClock] ?: false,
            macroTutorialSeen = prefs[Keys.macroTutorialSeen] ?: false
        )
    }

    suspend fun setAccent(value: Int) { context.brainSettings.edit { it[Keys.accent] = value } }
    suspend fun setCity(value: String) { context.brainSettings.edit { it[Keys.city] = value.trim().take(80) } }
    suspend fun setPhrase(value: String) { context.brainSettings.edit { it[Keys.phrase] = value.trim().take(160) } }
    suspend fun setFeed(value: String) { context.brainSettings.edit { it[Keys.feed] = value.trim().take(500) } }
    suspend fun setVoiceReplies(value: Boolean) { context.brainSettings.edit { it[Keys.voice] = value } }
    suspend fun setUseAdvancedVosk(value: Boolean) = setVoiceEngineMode(if (value) VoiceEngineMode.OFFLINE else VoiceEngineMode.AUTO)
    suspend fun setVoiceEngineMode(value: VoiceEngineMode) {
        context.brainSettings.edit {
            it[Keys.voiceEngineMode] = value.name
            it[Keys.useAdvancedVosk] = value == VoiceEngineMode.OFFLINE
        }
    }
    suspend fun setAutoSendVoiceTranscript(value: Boolean) { context.brainSettings.edit { it[Keys.autoSendVoiceTranscript] = value } }
    suspend fun markSelfCheckSeen() { context.brainSettings.edit { it[Keys.selfCheckSeen] = true } }
    suspend fun setNotificationPolicy(allowlist: String, blocklist: String) {
        context.brainSettings.edit {
            it[Keys.notificationAllowlist] = cleanPackageList(allowlist)
            it[Keys.notificationBlocklist] = cleanPackageList(blocklist)
        }
    }
    suspend fun setNotificationRetentionDays(value: Int) { context.brainSettings.edit { it[Keys.notificationRetentionDays] = value.coerceIn(1, 365) } }
    suspend fun setClipboardHistoryEnabled(value: Boolean) { context.brainSettings.edit { it[Keys.clipboardHistoryEnabled] = value } }
    suspend fun setAppLockEnabled(value: Boolean) {
        AppLockStateStore.setEnabled(context, value)
        context.brainSettings.edit { it[Keys.appLockEnabled] = value }
    }
    suspend fun setAppLockTimeoutSeconds(value: Int) {
        val bounded = AppLockPolicy.normalizeTimeoutSeconds(value)
        AppLockStateStore.setTimeoutSeconds(context, bounded)
        context.brainSettings.edit { it[Keys.appLockTimeoutSeconds] = bounded }
    }
    suspend fun setHideRecentsPreview(value: Boolean) { context.brainSettings.edit { it[Keys.hideRecentsPreview] = value } }
    suspend fun setAutoBackupEnabled(value: Boolean) { context.brainSettings.edit { it[Keys.autoBackupEnabled] = value } }
    suspend fun setBackgroundRefreshEnabled(value: Boolean) { context.brainSettings.edit { it[Keys.backgroundRefreshEnabled] = value } }
    suspend fun setNotificationKeepAliveEnabled(value: Boolean) {
        context.brainSettings.edit { it[Keys.notificationKeepAliveEnabled] = value }
        context.getSharedPreferences("notification_keep_alive", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
    suspend fun setNotificationLiveCaptureEnabled(value: Boolean) { context.brainSettings.edit { it[Keys.notificationLiveCaptureEnabled] = value } }
    suspend fun setMirrorAlarmsToSystemClock(value: Boolean) { context.brainSettings.edit { it[Keys.mirrorAlarmsToSystemClock] = value } }
    suspend fun setMacroTutorialSeen(seen: Boolean = true) { context.brainSettings.edit { it[Keys.macroTutorialSeen] = seen } }
    suspend fun setNotificationCategoryOverride(packageName: String, category: String) {
        val pkg = packageName.trim().take(180)
        val allowed = com.cue.offlinebrain.notifications.NotificationCategory.labels
        if (!pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) || category !in allowed) return
        context.brainSettings.edit { prefs ->
            val rows = prefs[Keys.notificationCategoryOverrides].orEmpty().lines().mapNotNull { line ->
                val split = line.indexOf('='); if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }.filterNot { it.first.equals(pkg, true) }.toMutableList()
            rows += pkg to category
            prefs[Keys.notificationCategoryOverrides] = rows.takeLast(100).joinToString("\n") { "${it.first}=${it.second}" }
        }
    }
    suspend fun restorePortablePreferences(payload: JSONObject) {
        val phrases = (0 until (payload.optJSONArray("learnedPhrases")?.length() ?: 0)).mapNotNull { index ->
            val row = payload.optJSONArray("learnedPhrases")?.optJSONObject(index) ?: return@mapNotNull null
            val phrase = row.optString("phrase").trim().replace('|', ' ').replace('\n', ' ').take(120)
            val intent = row.optString("intent")
            val argument = row.optString("argument").replace('|', ' ').replace('\n', ' ').take(200)
            if (phrase.isNotBlank() && intent in SUPPORTED_PHRASE_INTENTS) LearnedPhrase(phrase, intent, argument) else null
        }.distinctBy { it.phrase.lowercase() }.take(MAX_LEARNED_PHRASES)
        context.brainSettings.edit { prefs ->
            prefs[Keys.accent] = payload.optInt("accent", 0xFFA78BFA.toInt())
            prefs[Keys.city] = payload.optString("defaultCity", "New Delhi").take(80)
            prefs[Keys.phrase] = payload.optString("customPhrase").take(160)
            prefs[Keys.feed] = payload.optString("newsFeed", "https://feeds.bbci.co.uk/news/world/rss.xml").take(500)
            prefs[Keys.voice] = payload.optBoolean("voiceReplies", false)
            prefs[Keys.fontScale] = payload.optInt("fontScale", 100).coerceIn(85, 125)
            prefs[Keys.apiUrl] = payload.optString("customApiUrl").take(1500)
            prefs[Keys.jsonPath] = payload.optString("customJsonPath").take(300)
            prefs[Keys.snoozeMinutes] = payload.optInt("snoozeMinutes", 5).coerceIn(1, 60)
            val voiceMode = payload.optString("voiceEngineMode").let { runCatching { VoiceEngineMode.valueOf(it) }.getOrNull() }
                ?: if (payload.optBoolean("useAdvancedVosk", false)) VoiceEngineMode.OFFLINE else VoiceEngineMode.AUTO
            prefs[Keys.voiceEngineMode] = voiceMode.name
            prefs[Keys.useAdvancedVosk] = voiceMode == VoiceEngineMode.OFFLINE
            prefs[Keys.autoSendVoiceTranscript] = payload.optBoolean("autoSendVoiceTranscript", false)
            prefs[Keys.notificationAllowlist] = cleanPackageList(payload.optString("notificationAllowlist"))
            prefs[Keys.notificationBlocklist] = cleanPackageList(payload.optString("notificationBlocklist"))
            prefs[Keys.notificationRetentionDays] = payload.optInt("notificationRetentionDays", 30).coerceIn(1, 365)
            prefs[Keys.notificationCategoryOverrides] = payload.optString("notificationCategoryOverrides").lines().take(100).joinToString("\n")
            // Restored backups do not silently restart collection or a persistent listener service.
            prefs[Keys.notificationLiveCaptureEnabled] = false
            prefs[Keys.notificationKeepAliveEnabled] = false
            prefs[Keys.mirrorAlarmsToSystemClock] = payload.optBoolean("mirrorAlarmsToSystemClock", false)
            prefs[Keys.learnedPhrases] = phrases.joinToString("\n") { "${it.intent}|${it.phrase}|${it.argument}" }
        }
        val headers = payload.optString("customApiHeaders").take(4000)
        if (headers.isBlank()) clearCustomApiHeaders() else KeystoreSecretStore.put(context, "custom_api_headers", headers)
        check(context.getSharedPreferences("notification_keep_alive", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()) {
            "Android could not update the notification keep-alive setting."
        }
    }

    /** Complete local-only snapshot used solely by the encrypted restore rollback journal. */
    suspend fun rollbackSnapshot(): JSONObject {
        val p = settings.first()
        val headers = KeystoreSecretStore.get(context, "custom_api_headers")
            ?: KeystoreSecretStore.migrateLegacy(context, "brain_api_secrets", "custom_headers", "custom_api_headers").orEmpty()
        return JSONObject()
            .put("accent", p.accent).put("defaultCity", p.defaultCity).put("customPhrase", p.customPhrase)
            .put("newsFeed", p.newsFeed).put("voiceReplies", p.voiceReplies).put("fontScale", p.fontScale)
            .put("customApiUrl", p.customApiUrl).put("customJsonPath", p.customJsonPath).put("customApiHeaders", headers)
            .put("snoozeMinutes", p.snoozeMinutes).put("alarmSoundUri", p.alarmSoundUri)
            .put("useAdvancedVosk", p.useAdvancedVosk).put("voiceEngineMode", p.voiceEngineMode.name).put("autoSendVoiceTranscript", p.autoSendVoiceTranscript)
            .put("selfCheckSeen", p.selfCheckSeen).put("notificationAllowlist", p.notificationAllowlist)
            .put("notificationBlocklist", p.notificationBlocklist).put("notificationRetentionDays", p.notificationRetentionDays)
            .put("clipboardHistoryEnabled", p.clipboardHistoryEnabled).put("appLockEnabled", p.appLockEnabled)
            .put("appLockTimeoutSeconds", p.appLockTimeoutSeconds).put("hideRecentsPreview", p.hideRecentsPreview)
            .put("autoBackupEnabled", p.autoBackupEnabled).put("backgroundRefreshEnabled", p.backgroundRefreshEnabled)
            .put("notificationCategoryOverrides", p.notificationCategoryOverrides)
            .put("notificationLiveCaptureEnabled", p.notificationLiveCaptureEnabled)
            .put("notificationKeepAliveEnabled", p.notificationKeepAliveEnabled)
            .put("mirrorAlarmsToSystemClock", p.mirrorAlarmsToSystemClock).put("macroTutorialSeen", p.macroTutorialSeen)
            .put("lastIntent", p.lastIntent).put("lastCity", p.lastCity).put("lastAlarmRequest", p.lastAlarmRequest)
            .put("learnedPhrases", org.json.JSONArray().apply { p.learnedPhrases.forEach { put(JSONObject().put("phrase", it.phrase).put("intent", it.intent).put("argument", it.argument)) } })
    }

    /** Restores every local preference and mirror captured by [rollbackSnapshot]. */
    suspend fun restoreRollbackSnapshot(payload: JSONObject) {
        val phrases = (0 until (payload.optJSONArray("learnedPhrases")?.length() ?: 0)).mapNotNull { index ->
            val row = payload.optJSONArray("learnedPhrases")?.optJSONObject(index) ?: return@mapNotNull null
            val phrase = row.optString("phrase").trim().replace('|', ' ').replace('\n', ' ').take(120)
            val intent = row.optString("intent")
            val argument = row.optString("argument").replace('|', ' ').replace('\n', ' ').take(200)
            if (phrase.isNotBlank() && intent in SUPPORTED_PHRASE_INTENTS) LearnedPhrase(phrase, intent, argument) else null
        }.distinctBy { it.phrase.lowercase() }.take(MAX_LEARNED_PHRASES)
        val lockEnabled = payload.optBoolean("appLockEnabled", false)
        val lockTimeout = AppLockPolicy.normalizeTimeoutSeconds(payload.optInt("appLockTimeoutSeconds", AppLockPolicy.DEFAULT_TIMEOUT_SECONDS))
        val snooze = payload.optInt("snoozeMinutes", 5).coerceIn(1, 60)
        val sound = payload.optString("alarmSoundUri").take(1000)
        context.brainSettings.edit { prefs ->
            prefs.clear()
            prefs[Keys.accent] = payload.optInt("accent", 0xFFA78BFA.toInt())
            prefs[Keys.city] = payload.optString("defaultCity", "New Delhi").take(80)
            prefs[Keys.phrase] = payload.optString("customPhrase").take(160)
            prefs[Keys.feed] = payload.optString("newsFeed", "https://feeds.bbci.co.uk/news/world/rss.xml").take(500)
            prefs[Keys.voice] = payload.optBoolean("voiceReplies", false)
            prefs[Keys.fontScale] = payload.optInt("fontScale", 100).coerceIn(85, 125)
            prefs[Keys.apiUrl] = payload.optString("customApiUrl").take(1500)
            prefs[Keys.jsonPath] = payload.optString("customJsonPath").take(300)
            prefs[Keys.snoozeMinutes] = snooze
            prefs[Keys.alarmSoundUri] = sound
            val voiceMode = payload.optString("voiceEngineMode").let { runCatching { VoiceEngineMode.valueOf(it) }.getOrNull() }
                ?: if (payload.optBoolean("useAdvancedVosk", false)) VoiceEngineMode.OFFLINE else VoiceEngineMode.AUTO
            prefs[Keys.voiceEngineMode] = voiceMode.name
            prefs[Keys.useAdvancedVosk] = voiceMode == VoiceEngineMode.OFFLINE
            prefs[Keys.autoSendVoiceTranscript] = payload.optBoolean("autoSendVoiceTranscript", false)
            prefs[Keys.selfCheckSeen] = payload.optBoolean("selfCheckSeen", false)
            prefs[Keys.notificationAllowlist] = cleanPackageList(payload.optString("notificationAllowlist"))
            prefs[Keys.notificationBlocklist] = cleanPackageList(payload.optString("notificationBlocklist"))
            prefs[Keys.notificationRetentionDays] = payload.optInt("notificationRetentionDays", 30).coerceIn(1, 365)
            prefs[Keys.clipboardHistoryEnabled] = payload.optBoolean("clipboardHistoryEnabled", false)
            prefs[Keys.appLockEnabled] = lockEnabled
            prefs[Keys.appLockTimeoutSeconds] = lockTimeout
            prefs[Keys.hideRecentsPreview] = payload.optBoolean("hideRecentsPreview", false)
            prefs[Keys.autoBackupEnabled] = payload.optBoolean("autoBackupEnabled", false)
            prefs[Keys.backgroundRefreshEnabled] = payload.optBoolean("backgroundRefreshEnabled", false)
            prefs[Keys.notificationCategoryOverrides] = payload.optString("notificationCategoryOverrides").lines().take(100).joinToString("\n")
            prefs[Keys.notificationLiveCaptureEnabled] = payload.optBoolean("notificationLiveCaptureEnabled", false)
            prefs[Keys.notificationKeepAliveEnabled] = payload.optBoolean("notificationKeepAliveEnabled", false)
            prefs[Keys.mirrorAlarmsToSystemClock] = payload.optBoolean("mirrorAlarmsToSystemClock", false)
            prefs[Keys.macroTutorialSeen] = payload.optBoolean("macroTutorialSeen", false)
            prefs[Keys.lastIntent] = payload.optString("lastIntent").take(32)
            prefs[Keys.lastCity] = payload.optString("lastCity").take(80)
            prefs[Keys.lastAlarmRequest] = payload.optString("lastAlarmRequest").take(160)
            prefs[Keys.learnedPhrases] = phrases.joinToString("\n") { "${it.intent}|${it.phrase}|${it.argument}" }
        }
        AppLockStateStore.setTimeoutSeconds(context, lockTimeout)
        AppLockStateStore.setEnabled(context, lockEnabled)
        check(context.getSharedPreferences("notification_keep_alive", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", payload.optBoolean("notificationKeepAliveEnabled", false)).commit()) {
            "Android could not restore the notification keep-alive setting."
        }
        check(context.getSharedPreferences("alarm_preferences", Context.MODE_PRIVATE).edit()
            .putInt("snooze_minutes", snooze).putString("sound_uri", sound).commit()) {
            "Android could not restore the alarm settings."
        }
        val headers = payload.optString("customApiHeaders").take(4000)
        if (headers.isBlank()) clearCustomApiHeaders() else KeystoreSecretStore.put(context, "custom_api_headers", headers)
    }
    suspend fun clearAllSettings() {
        context.brainSettings.edit { it.clear() }
        AppLockStateStore.setEnabled(context, false)
        clearCustomApiHeaders()
        KeystoreSecretStore.remove(context, "app_pin_hash")
        KeystoreSecretStore.remove(context, "scheduled_backup_key")
        context.getSharedPreferences("alarm_preferences", Context.MODE_PRIVATE).edit().clear().apply()
    }
    suspend fun setFontScale(value: Int) { context.brainSettings.edit { it[Keys.fontScale] = value.coerceIn(85, 125) } }
    suspend fun setSnoozeMinutes(value: Int) {
        val minutes = value.coerceIn(1, 60)
        context.brainSettings.edit { it[Keys.snoozeMinutes] = minutes }
        context.getSharedPreferences("alarm_preferences", Context.MODE_PRIVATE).edit().putInt("snooze_minutes", minutes).apply()
    }

    suspend fun setAlarmSound(uri: String) {
        val cleaned = uri.take(1000)
        context.brainSettings.edit { it[Keys.alarmSoundUri] = cleaned }
        // A synchronous mirror lets Android create the notification channel and ringtone before the next event.
        context.getSharedPreferences("alarm_preferences", Context.MODE_PRIVATE).edit().putString("sound_uri", cleaned).apply()
    }

    suspend fun rememberContext(intent: String, city: String = "", alarmRequest: String = "") {
        context.brainSettings.edit {
            it[Keys.lastIntent] = intent.take(32)
            it[Keys.lastCity] = city.take(80)
            it[Keys.lastAlarmRequest] = alarmRequest.take(160)
        }
    }

    suspend fun addLearnedPhrase(phrase: String, intent: String, argument: String = "") {
        val cleanPhrase = phrase.trim().replace('|', ' ').replace('\n', ' ').take(120)
        if (cleanPhrase.isBlank() || intent !in SUPPORTED_PHRASE_INTENTS) return
        val cleanArgument = argument.trim().replace('|', ' ').replace('\n', ' ').take(200)
        context.brainSettings.edit { prefs ->
            val rows = parsePhrases(prefs[Keys.learnedPhrases].orEmpty()).filterNot { it.phrase.equals(cleanPhrase, true) }.toMutableList()
            rows.add(0, LearnedPhrase(cleanPhrase, intent, cleanArgument))
            prefs[Keys.learnedPhrases] = rows.take(MAX_LEARNED_PHRASES).joinToString("\n") { "${it.intent}|${it.phrase}|${it.argument}" }
        }
    }

    suspend fun deleteLearnedPhrase(phrase: String) {
        context.brainSettings.edit { prefs ->
            prefs[Keys.learnedPhrases] = parsePhrases(prefs[Keys.learnedPhrases].orEmpty())
                .filterNot { it.phrase.equals(phrase, true) }.joinToString("\n") { "${it.intent}|${it.phrase}|${it.argument}" }
        }
    }

    suspend fun setCustomApi(url: String, path: String, headers: String) {
        context.brainSettings.edit { it[Keys.apiUrl] = url.trim().take(1500); it[Keys.jsonPath] = path.trim().take(300) }
        if (headers.isNotBlank()) KeystoreSecretStore.put(context, "custom_api_headers", headers.take(4000))
    }
    fun customApiHeaders(): String = runCatching {
        KeystoreSecretStore.get(context, "custom_api_headers")
            ?: KeystoreSecretStore.migrateLegacy(context, "brain_api_secrets", "custom_headers", "custom_api_headers")
    }.onFailure { CrashLog.record("SECRETS", "custom API headers could not be read", it) }.getOrNull().orEmpty()
    fun clearCustomApiHeaders() {
        KeystoreSecretStore.remove(context, "custom_api_headers")
        KeystoreSecretStore.removeLegacy(context, "brain_api_secrets", "custom_headers")
    }

    private fun parsePhrases(raw: String): List<LearnedPhrase> = raw.lines().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        val intent = parts.getOrNull(0).orEmpty()
        val phrase = parts.getOrNull(1).orEmpty().trim()
        val argument = parts.getOrNull(2).orEmpty().trim()
        if (intent in SUPPORTED_PHRASE_INTENTS && phrase.isNotBlank()) LearnedPhrase(phrase, intent, argument) else null
    }.take(MAX_LEARNED_PHRASES)

    private fun cleanPackageList(value: String): String = value.lines().map { it.trim().take(180) }
        .filter { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) }.distinct().take(80).joinToString("\n")

    companion object {
        val SUPPORTED_PHRASE_INTENTS = setOf("weather", "news", "notifications", "alarm", "pdf", "open_app", "macro", "custom_reply", "morning", "command", "chain", "device_status", "search", "note")
        private const val MAX_LEARNED_PHRASES = 50
    }
}
