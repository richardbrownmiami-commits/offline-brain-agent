package com.cue.offlinebrain.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

data class EncryptedBackupPreview(
    val messages: Int,
    val alarms: Int,
    val notes: Int,
    val notifications: Int,
    val rules: Int,
    val macros: Int,
    val clipboardEntries: Int,
    val phrases: Int,
    val settings: Int
)

object EncryptedBackup {
    private const val FORMAT_VERSION = 1

    private data class RestoreRows(
        val messages: List<MessageEntity>,
        val alarms: List<AlarmEntity>,
        val notes: List<NoteEntity>,
        val notifications: List<NotificationEntity>,
        val rules: List<AutomationRuleEntity>,
        val macros: List<MacroEntity>,
        val clipboard: List<ClipboardEntryEntity>,
        val workerCache: List<WorkerCacheEntity>
    )

    fun preview(passphrase: String, encrypted: ByteArray): EncryptedBackupPreview {
        val root = decodeRoot(passphrase, encrypted)
        val arrays = requiredArrays(root)
        val counts = arrays.associateWith { root.optJSONArray(it)?.length() ?: 0 }
        return EncryptedBackupPreview(
            messages = counts["messages"] ?: 0,
            alarms = counts["alarms"] ?: 0,
            notes = counts["notes"] ?: 0,
            notifications = counts["notifications"] ?: 0,
            rules = counts["rules"] ?: 0,
            macros = counts["macros"] ?: 0,
            clipboardEntries = counts["clipboard"] ?: 0,
            phrases = root.optJSONObject("preferences")?.optJSONArray("learnedPhrases")?.length() ?: 0,
            settings = root.optJSONObject("preferences")?.length() ?: 0
        )
    }

    suspend fun export(database: BrainDatabase, passphrase: String, settings: SettingsStore? = null): ByteArray {
        val json = exportPayload(database, settings)
        return PortableBackupCrypto.encrypt(passphrase, json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun restore(database: BrainDatabase, passphrase: String, encrypted: ByteArray, settings: SettingsStore? = null) {
        val incomingRoot = decodeRoot(passphrase, encrypted)
        val incoming = parseRows(incomingRoot)
        if (settings == null) {
            replaceDatabase(database, incoming)
            return
        }

        // Save and durably publish the encrypted old-state journal before either live store changes.
        val snapshot = exportPayload(database, settings)
            .put("restoreSettingsSnapshot", settings.rollbackSnapshot())
            .put("restoreWorkerCache", JSONArray().apply {
                database.dao().allWorkerCacheForRestore().forEach { put(JSONObject().put("key", it.key).put("content", it.content).put("updatedAt", it.updatedAt)) }
            })
        val journalKey = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val journalBytes = PortableBackupCrypto.encrypt(journalKey, snapshot.toString().toByteArray(StandardCharsets.UTF_8))
        RestoreRollbackJournal.write(settings.applicationContext, journalKey, journalBytes)
        val oldRows = parseRows(snapshot, includeJournalCache = true)
        var rollbackCompleted = false
        val rollback = suspend {
            replaceDatabase(database, oldRows)
            settings.restoreRollbackSnapshot(snapshot.getJSONObject("restoreSettingsSnapshot"))
            rollbackCompleted = true
            CrashLog.record("BACKUP", "restore rollback completed from encrypted journal")
        }

        try {
            RestoreTransaction.apply(
                replaceDatabase = { replaceDatabase(database, incoming) },
                replaceSettings = { incomingRoot.optJSONObject("preferences")?.let { settings.restorePortablePreferences(it) } },
                rollbackBoth = rollback,
                onRollbackFailure = { error -> CrashLog.record("BACKUP", "restore rollback failed; journal retained", error) }
            )
            try {
                RestoreRollbackJournal.clear(settings.applicationContext)
            } catch (clearError: Exception) {
                try {
                    withContext(kotlinx.coroutines.NonCancellable) { rollback(); RestoreRollbackJournal.clear(settings.applicationContext) }
                } catch (rollbackError: Exception) {
                    clearError.addSuppressed(rollbackError)
                    throw RestoreRollbackPendingException(clearError)
                }
                throw clearError
            }
            CrashLog.record("BACKUP", "encrypted cross-store restore committed; rollback journal removed")
        } catch (cancelled: CancellationException) {
            if (rollbackCompleted) runCatching { RestoreRollbackJournal.clear(settings.applicationContext) }
                .onFailure { CrashLog.record("BACKUP", "completed rollback journal cleanup deferred to startup", it) }
            throw cancelled
        } catch (pending: RestoreRollbackPendingException) {
            throw pending
        } catch (error: Exception) {
            if (rollbackCompleted) {
                try { RestoreRollbackJournal.clear(settings.applicationContext) }
                catch (clearError: Exception) {
                    error.addSuppressed(clearError)
                    throw RestoreRollbackPendingException(error)
                }
            }
            throw error
        }
    }

    fun hasPendingRestoreJournal(context: Context): Boolean = RestoreRollbackJournal.exists(context)

    /** Called synchronously by Application startup only when a journal file exists. */
    suspend fun recoverPendingRestore(database: BrainDatabase, settings: SettingsStore) {
        val (key, encrypted) = RestoreRollbackJournal.read(settings.applicationContext)
            ?: return
        val root = decodeRoot(key, encrypted)
        require(root.optJSONObject("restoreSettingsSnapshot") != null) { "Saved restore journal has no settings snapshot." }
        val rows = parseRows(root, includeJournalCache = true)
        replaceDatabase(database, rows)
        settings.restoreRollbackSnapshot(root.getJSONObject("restoreSettingsSnapshot"))
        RestoreRollbackJournal.clear(settings.applicationContext)
        CrashLog.record("BACKUP", "interrupted restore recovered to the previous committed database and settings")
    }

    private suspend fun exportPayload(database: BrainDatabase, settings: SettingsStore?): JSONObject {
        val dao = database.dao()
        val json = JSONObject().put("version", FORMAT_VERSION)
        json.put("messages", JSONArray().apply { dao.allMessagesForBackup().forEach { put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("timestamp", it.timestamp)) } })
        json.put("alarms", JSONArray().apply { dao.allAlarmsForBackup().forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("triggerAt", it.triggerAt).put("cron", it.cron).put("enabled", it.enabled)) } })
        json.put("notes", JSONArray().apply { dao.allNotesForBackup().forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done).put("updatedAt", it.updatedAt)) } })
        json.put("notifications", JSONArray().apply { dao.allNotificationsForBackup().forEach { put(JSONObject().put("id", it.id).put("packageName", it.packageName).put("title", it.title).put("text", it.text).put("postedAt", it.postedAt).put("dedupeKey", it.dedupeKey).put("textHash", it.textHash).put("category", it.category).put("isOngoing", it.isOngoing).put("groupName", it.groupName).put("isRead", it.isRead)) } })
        json.put("rules", JSONArray().apply { dao.allAutomationRulesForBackup().forEach { put(JSONObject().put("id", it.id).put("triggerPhrase", it.triggerPhrase).put("actionText", it.actionText).put("enabled", it.enabled).put("createdAt", it.createdAt)) } })
        json.put("macros", JSONArray().apply { dao.allMacrosForBackup().forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("steps", it.steps).put("updatedAt", it.updatedAt)) } })
        json.put("clipboard", JSONArray().apply { dao.allClipboardForBackup().forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("copiedAt", it.copiedAt)) } })
        if (settings != null) {
            val prefs = settings.settings.first()
            json.put("preferences", JSONObject()
                .put("accent", prefs.accent).put("defaultCity", prefs.defaultCity).put("customPhrase", prefs.customPhrase)
                .put("newsFeed", prefs.newsFeed).put("voiceReplies", prefs.voiceReplies).put("fontScale", prefs.fontScale)
                .put("customApiUrl", prefs.customApiUrl).put("customJsonPath", prefs.customJsonPath)
                .put("customApiHeaders", settings.customApiHeaders()).put("snoozeMinutes", prefs.snoozeMinutes)
                .put("useAdvancedVosk", prefs.useAdvancedVosk).put("notificationAllowlist", prefs.notificationAllowlist)
                .put("notificationBlocklist", prefs.notificationBlocklist).put("notificationRetentionDays", prefs.notificationRetentionDays)
                .put("notificationCategoryOverrides", prefs.notificationCategoryOverrides).put("notificationLiveCaptureEnabled", prefs.notificationLiveCaptureEnabled)
                .put("autoSendVoiceTranscript", prefs.autoSendVoiceTranscript)
                .put("notificationKeepAliveEnabled", prefs.notificationKeepAliveEnabled)
                .put("mirrorAlarmsToSystemClock", prefs.mirrorAlarmsToSystemClock)
                .put("learnedPhrases", JSONArray().apply { prefs.learnedPhrases.forEach { put(JSONObject().put("phrase", it.phrase).put("intent", it.intent).put("argument", it.argument)) } })
            )
        }
        return json
    }

    private fun decodeRoot(passphrase: String, encrypted: ByteArray): JSONObject {
        val root = JSONObject(String(PortableBackupCrypto.decrypt(passphrase, encrypted), StandardCharsets.UTF_8))
        require(root.optInt("version", -1) == FORMAT_VERSION) { "This backup version is not supported." }
        requiredArrays(root)
        return root
    }

    private fun requiredArrays(root: JSONObject): List<String> {
        val arrays = listOf("messages", "alarms", "notes", "notifications", "rules", "macros", "clipboard")
        require(arrays.all { root.optJSONArray(it) != null }) { "This backup is incomplete or damaged. Nothing was restored." }
        val sizes = arrays.associateWith { root.optJSONArray(it)?.length() ?: 0 }
        require((sizes["messages"] ?: 0) <= 100_000 && (sizes["alarms"] ?: 0) <= 10_000 &&
            (sizes["notes"] ?: 0) <= 100_000 && (sizes["notifications"] ?: 0) <= 100_000 &&
            (sizes["rules"] ?: 0) <= 2_000 && (sizes["macros"] ?: 0) <= 2_000 && (sizes["clipboard"] ?: 0) <= 100_000) {
            "This backup contains too many records. Nothing was restored."
        }
        return arrays
    }

    private fun parseRows(root: JSONObject, includeJournalCache: Boolean = false): RestoreRows {
        val messages = root.array("messages").map { MessageEntity(it.getLong("id"), it.getString("role"), it.getString("text"), it.getLong("timestamp")) }
        val alarms = root.array("alarms").map { AlarmEntity(it.getLong("id"), it.getString("label"), it.getLong("triggerAt"), it.optString("cron").takeIf { value -> value.isNotBlank() }, it.optBoolean("enabled")) }
        val notes = root.array("notes").map { NoteEntity(it.getLong("id"), it.getString("text"), it.optBoolean("done"), it.getLong("updatedAt")) }
        val notifications = root.array("notifications").map { row ->
            val id = row.getLong("id")
            NotificationEntity(id, row.getString("packageName"), row.getString("title"), row.getString("text"), row.getLong("postedAt"),
                row.optString("dedupeKey").ifBlank { "backup-$id" }, row.optString("textHash").ifBlank { "backup-$id" },
                row.optString("category", "Other"), row.optBoolean("isOngoing"), row.optString("groupName"), row.optBoolean("isRead"))
        }
        val rules = root.array("rules").map { AutomationRuleEntity(it.getLong("id"), it.getString("triggerPhrase"), it.getString("actionText"), it.optBoolean("enabled"), it.getLong("createdAt")) }
        val macros = root.array("macros").map { MacroEntity(it.getLong("id"), it.getString("name"), it.getString("steps"), it.getLong("updatedAt")) }
        val clipboard = root.array("clipboard").map { ClipboardEntryEntity(it.getLong("id"), it.getString("text"), it.getLong("copiedAt")) }
        val workerCache = if (includeJournalCache) root.array("restoreWorkerCache").map {
            WorkerCacheEntity(it.getString("key"), it.getString("content"), it.getLong("updatedAt"))
        } else emptyList()
        return RestoreRows(messages, alarms, notes, notifications, rules, macros, clipboard, workerCache)
    }

    private suspend fun replaceDatabase(database: BrainDatabase, rows: RestoreRows) {
        database.withTransaction {
            val dao = database.dao()
            dao.clearAllMessages(); dao.clearAllAlarms(); dao.clearAllNotes(); dao.clearAllNotifications()
            dao.clearAllAutomationRules(); dao.clearAllMacros(); dao.clearAllClipboardHistory(); dao.clearAllWorkerCache()
            if (rows.messages.isNotEmpty()) dao.insertMessages(rows.messages)
            if (rows.alarms.isNotEmpty()) dao.insertAlarms(rows.alarms)
            if (rows.notes.isNotEmpty()) dao.insertNotes(rows.notes)
            if (rows.notifications.isNotEmpty()) dao.insertNotifications(rows.notifications)
            if (rows.rules.isNotEmpty()) dao.insertAutomationRules(rows.rules)
            if (rows.macros.isNotEmpty()) dao.insertMacros(rows.macros)
            if (rows.clipboard.isNotEmpty()) dao.insertClipboardEntries(rows.clipboard)
            if (rows.workerCache.isNotEmpty()) dao.insertWorkerCache(rows.workerCache)
        }
    }

    private fun JSONObject.array(name: String): List<JSONObject> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}

/** Private, authenticated journal; the encryption passphrase itself is stored only via Android Keystore. */
private object RestoreRollbackJournal {
    private const val FILE_NAME = "restore-rollback.journal"
    private const val TEMP_PREFIX = "restore-rollback.tmp-"
    private const val KEY_PREFIX = "restore_journal_key_"
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024 + 256

    fun exists(context: Context): Boolean = File(context.applicationContext.filesDir, FILE_NAME).isFile

    fun write(context: Context, key: String, encrypted: ByteArray) {
        val dir = context.applicationContext.filesDir
        val journal = File(dir, FILE_NAME)
        val oldId = readId(journal)
        val id = UUID.randomUUID().toString()
        val keyName = KEY_PREFIX + id
        KeystoreSecretStore.put(context, keyName, key)
        val temp = File(dir, TEMP_PREFIX + id)
        val prefix = "$id\n".toByteArray(StandardCharsets.US_ASCII)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(prefix)
                stream.write(encrypted)
                stream.fd.sync()
            }
            Files.move(temp.toPath(), journal.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(dir)
            oldId?.takeIf { it != id }?.let { KeystoreSecretStore.remove(context, KEY_PREFIX + it) }
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(temp.toPath()) }
            if (readId(journal) != id) KeystoreSecretStore.remove(context, keyName)
            CrashLog.record("BACKUP", "encrypted rollback journal could not be made durable", error)
            throw IllegalStateException("The previous data could not be safely staged. Nothing was restored.", error)
        }
    }

    fun read(context: Context): Pair<String, ByteArray>? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        require(file.isFile && file.length() in 38..MAX_FILE_BYTES.toLong()) { "Saved restore journal is missing or too large." }
        val bytes = file.readBytes()
        val separator = bytes.indexOf('\n'.code.toByte())
        require(separator in 36..40 && separator < bytes.lastIndex) { "Saved restore journal header is damaged." }
        val id = String(bytes, 0, separator, StandardCharsets.US_ASCII)
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Saved restore journal ID is invalid." }
        val key = KeystoreSecretStore.get(context, KEY_PREFIX + id)
            ?: throw IllegalStateException("The encrypted restore journal key is unavailable.")
        return key to bytes.copyOfRange(separator + 1, bytes.size)
    }

    fun clear(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val dir = file.parentFile ?: throw IllegalStateException("Restore journal directory is unavailable.")
        val priorBytes = file.takeIf(File::isFile)?.readBytes()
        val id = readId(file)
        try {
            Files.deleteIfExists(file.toPath())
            syncDirectory(dir)
        } catch (error: Exception) {
            // A failed directory sync after unlink is ambiguous; put the encrypted journal back
            // before the caller attempts data rollback, so startup recovery remains possible.
            if (priorBytes != null && !file.exists()) {
                val restoreTemp = File(dir, TEMP_PREFIX + UUID.randomUUID())
                try {
                    FileOutputStream(restoreTemp).use { stream -> stream.write(priorBytes); stream.fd.sync() }
                    Files.move(restoreTemp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    syncDirectory(dir)
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                    CrashLog.record("BACKUP", "deleted rollback journal could not be republished", restoreError)
                    runCatching { Files.deleteIfExists(restoreTemp.toPath()) }
                }
            }
            throw error
        }
        id?.let { keyId ->
            try { KeystoreSecretStore.remove(context, KEY_PREFIX + keyId) }
            catch (error: Exception) { CrashLog.record("BACKUP", "orphaned rollback journal key could not be removed", error) }
        }
    }

    private fun readId(file: File): String? = try {
        if (!file.isFile || file.length() > 64L) return null
        val first = file.readBytes().takeWhile { it != '\n'.code.toByte() }.toByteArray()
        String(first, StandardCharsets.US_ASCII).takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
    } catch (error: Exception) {
        CrashLog.record("BACKUP", "rollback journal header could not be read", error)
        null
    }

    private fun syncDirectory(dir: File) {
        val descriptor = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
