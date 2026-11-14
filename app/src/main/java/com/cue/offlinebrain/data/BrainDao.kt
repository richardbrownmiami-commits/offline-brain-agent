package com.cue.offlinebrain.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BrainDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 120")
    fun observeLatestMessages(): Flow<List<MessageEntity>>
    @Insert suspend fun addMessage(message: MessageEntity): Long
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("SELECT * FROM messages ORDER BY timestamp") suspend fun allMessagesForBackup(): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessages(rows: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedFaqs(rows: List<FaqEntity>)
    @Query("SELECT rowid, question, answer, phrases FROM faqs WHERE faqs MATCH :match LIMIT 5")
    suspend fun searchFaq(match: String): List<FaqEntity>

    @Insert suspend fun addAlarm(alarm: AlarmEntity): Long
    @Query("SELECT * FROM alarms ORDER BY triggerAt")
    fun observeAlarms(): Flow<List<AlarmEntity>>
    @Query("SELECT * FROM alarms WHERE enabled = 1 ORDER BY triggerAt")
    suspend fun getEnabledAlarms(): List<AlarmEntity>
    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getAlarm(id: Long): AlarmEntity?
    @Query("UPDATE alarms SET enabled = 0 WHERE id = :id")
    suspend fun disableAlarm(id: Long)
    @Query("UPDATE alarms SET triggerAt = :time WHERE id = :id")
    suspend fun updateAlarmTime(id: Long, time: Long)
    @Query("UPDATE alarms SET label = :label, triggerAt = :triggerAt, cron = :cron, enabled = :enabled WHERE id = :id")
    suspend fun updateAlarm(id: Long, label: String, triggerAt: Long, cron: String?, enabled: Boolean)
    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setAlarmEnabled(id: Long, enabled: Boolean)
    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarm(id: Long)
    @Query("SELECT * FROM alarms ORDER BY triggerAt") suspend fun allAlarmsForBackup(): List<AlarmEntity>
    @Query("DELETE FROM alarms") suspend fun clearAlarms()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAlarms(rows: List<AlarmEntity>)

    @Insert suspend fun addNote(note: NoteEntity): Long
    @Query("SELECT * FROM notes ORDER BY done ASC, updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>
    @Query("UPDATE notes SET done = :done, updatedAt = :now WHERE id = :id")
    suspend fun setNoteDone(id: Long, done: Boolean, now: Long = System.currentTimeMillis())
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: Long)
    @Query("SELECT * FROM notes ORDER BY updatedAt") suspend fun allNotesForBackup(): List<NoteEntity>
    @Query("DELETE FROM notes") suspend fun clearNotes()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertNotes(rows: List<NoteEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun saveNotification(notification: NotificationEntity): Long
    @Query("SELECT * FROM notifications WHERE title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR packageName LIKE '%' || :query || '%' ORDER BY postedAt DESC LIMIT 20")
    suspend fun searchNotifications(query: String): List<NotificationEntity>
    @Query("SELECT * FROM notifications WHERE postedAt >= :since AND (title LIKE '%otp%' OR text LIKE '%otp%' OR text GLOB '*[0-9][0-9][0-9][0-9]*') ORDER BY postedAt DESC LIMIT 20")
    suspend fun searchRecentOtpNotifications(since: Long): List<NotificationEntity>
    @Query("SELECT * FROM notifications ORDER BY postedAt DESC LIMIT 20")
    suspend fun recentNotifications(): List<NotificationEntity>
    @Query("SELECT * FROM notifications ORDER BY postedAt DESC LIMIT 500")
    fun observeNotificationHistory(): Flow<List<NotificationEntity>>
    @Query("SELECT * FROM notifications WHERE category = :category OR (:category = 'Messages/SMS' AND category = 'Messages (SMS)') OR (:category = 'Food' AND category = 'Food and delivery') ORDER BY postedAt DESC LIMIT 200")
    suspend fun notificationsByCategory(category: String): List<NotificationEntity>
    @Query("SELECT CASE WHEN category = 'Messages (SMS)' THEN 'Messages/SMS' WHEN category = 'Food and delivery' THEN 'Food' ELSE category END AS category, COUNT(*) AS count FROM notifications GROUP BY CASE WHEN category = 'Messages (SMS)' THEN 'Messages/SMS' WHEN category = 'Food and delivery' THEN 'Food' ELSE category END ORDER BY category")
    suspend fun notificationCategoryCounts(): List<NotificationCategoryCount>
    @Query("SELECT CASE WHEN category = 'Messages (SMS)' THEN 'Messages/SMS' WHEN category = 'Food and delivery' THEN 'Food' ELSE category END AS category, COUNT(*) AS count FROM notifications GROUP BY CASE WHEN category = 'Messages (SMS)' THEN 'Messages/SMS' WHEN category = 'Food and delivery' THEN 'Food' ELSE category END ORDER BY category")
    fun observeNotificationCategoryCounts(): Flow<List<NotificationCategoryCount>>
    @Query("SELECT packageName, COUNT(*) AS count FROM notifications GROUP BY packageName ORDER BY packageName")
    suspend fun notificationPackageCounts(): List<NotificationPackageCount>
    @Query("SELECT COUNT(*) FROM notifications") suspend fun notificationRowCount(): Int
    @Query("SELECT MAX(postedAt) FROM notifications") suspend fun latestNotificationTime(): Long?
    @Query("UPDATE notifications SET isRead = 1") suspend fun markAllNotificationsRead()
    @Query("DELETE FROM notifications WHERE category = :category") suspend fun clearNotificationCategory(category: String)
    @Query("SELECT * FROM notifications WHERE packageName = :packageName OR packageName LIKE '%' || :packageName || '%' ORDER BY postedAt DESC LIMIT 20")
    suspend fun notificationsFromPackage(packageName: String): List<NotificationEntity>
    @Query("SELECT * FROM notifications WHERE packageName = :packageName AND (:since = 0 OR postedAt >= :since) AND (:unreadOnly = 0 OR isRead = 0) ORDER BY postedAt DESC LIMIT 200")
    suspend fun notificationsFromPackageFiltered(packageName: String, since: Long, unreadOnly: Boolean): List<NotificationEntity>
    @Query("SELECT * FROM notifications WHERE (category = :category OR (:category = 'Messages/SMS' AND category = 'Messages (SMS)') OR (:category = 'Food' AND category = 'Food and delivery')) AND (:since = 0 OR postedAt >= :since) AND (:unreadOnly = 0 OR isRead = 0) ORDER BY postedAt DESC LIMIT 200")
    suspend fun notificationsByCategoryFiltered(category: String, since: Long, unreadOnly: Boolean): List<NotificationEntity>
    @Query("SELECT * FROM notifications WHERE (:since = 0 OR postedAt >= :since) AND (:unreadOnly = 0 OR isRead = 0) ORDER BY postedAt DESC LIMIT 200")
    suspend fun recentNotificationsFiltered(since: Long, unreadOnly: Boolean): List<NotificationEntity>
    @Query("SELECT * FROM notifications WHERE (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%') AND (:since = 0 OR postedAt >= :since) AND (:unreadOnly = 0 OR isRead = 0) ORDER BY postedAt DESC LIMIT 200")
    suspend fun searchNotificationsFiltered(query: String, since: Long, unreadOnly: Boolean): List<NotificationEntity>
    @Query("UPDATE notifications SET isRead = 1 WHERE category = :category OR (:category = 'Messages/SMS' AND category = 'Messages (SMS)') OR (:category = 'Food' AND category = 'Food and delivery')")
    suspend fun markCategoryNotificationsRead(category: String)
    @Query("UPDATE notifications SET category = :category WHERE id = :id")
    suspend fun updateNotificationCategory(id: Long, category: String)
    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationRead(id: Long)
    @Query("UPDATE notifications SET category = :category WHERE packageName = :packageName")
    suspend fun updateNotificationCategoryForPackage(packageName: String, category: String)
    @Query("UPDATE notifications SET isRead = 1 WHERE packageName = :packageName")
    suspend fun markPackageNotificationsRead(packageName: String)
    @Query("DELETE FROM notifications WHERE postedAt < :before")
    suspend fun deleteNotificationsBefore(before: Long)
    @Query("DELETE FROM notifications WHERE dedupeKey = :key") suspend fun deleteNotificationByDedupeKey(key: String)
    @Query("SELECT * FROM notifications ORDER BY postedAt") suspend fun allNotificationsForBackup(): List<NotificationEntity>
    @Query("DELETE FROM notifications") suspend fun clearNotifications()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertNotifications(rows: List<NotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAutomationRule(rule: AutomationRuleEntity): Long
    @Query("SELECT * FROM automation_rules ORDER BY createdAt DESC") fun observeAutomationRules(): Flow<List<AutomationRuleEntity>>
    @Query("SELECT * FROM automation_rules WHERE enabled = 1 ORDER BY createdAt DESC") suspend fun enabledAutomationRules(): List<AutomationRuleEntity>
    @Query("DELETE FROM automation_rules WHERE lower(triggerPhrase) = lower(:trigger)") suspend fun deleteAutomationRulesByTrigger(trigger: String)
    @Query("DELETE FROM automation_rules WHERE id = :id") suspend fun deleteAutomationRule(id: Long)
    @Query("DELETE FROM automation_rules") suspend fun clearAutomationRules()
    @Query("SELECT * FROM automation_rules ORDER BY createdAt") suspend fun allAutomationRulesForBackup(): List<AutomationRuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAutomationRules(rows: List<AutomationRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMacro(macro: MacroEntity): Long
    @Query("SELECT * FROM macros WHERE id = :id LIMIT 1") suspend fun macroById(id: Long): MacroEntity?
    @Update suspend fun updateMacro(macro: MacroEntity): Int
    @Query("SELECT * FROM macros WHERE lower(name) = lower(:name) LIMIT 1") suspend fun macroByName(name: String): MacroEntity?
    @Query("SELECT * FROM macros ORDER BY updatedAt DESC") fun observeMacros(): Flow<List<MacroEntity>>
    @Query("DELETE FROM macros WHERE lower(name) = lower(:name)") suspend fun deleteMacrosByName(name: String)
    @Query("DELETE FROM macros WHERE id = :id") suspend fun deleteMacro(id: Long)
    @Query("DELETE FROM macros") suspend fun clearMacros()
    @Query("SELECT * FROM macros ORDER BY updatedAt") suspend fun allMacrosForBackup(): List<MacroEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMacros(rows: List<MacroEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addClipboardEntry(entry: ClipboardEntryEntity): Long
    @Query("SELECT * FROM clipboard_history ORDER BY copiedAt DESC LIMIT 100") fun observeClipboardHistory(): Flow<List<ClipboardEntryEntity>>
    @Query("SELECT text FROM clipboard_history ORDER BY copiedAt DESC LIMIT 1") suspend fun latestClipboardText(): String?
    @Query("DELETE FROM clipboard_history WHERE copiedAt < :before") suspend fun deleteClipboardBefore(before: Long)
    @Query("DELETE FROM clipboard_history") suspend fun clearClipboardHistory()
    @Query("SELECT * FROM clipboard_history ORDER BY copiedAt") suspend fun allClipboardForBackup(): List<ClipboardEntryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertClipboardEntries(rows: List<ClipboardEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWorkerCache(row: WorkerCacheEntity)
    @Query("SELECT * FROM worker_cache WHERE `key` = :key LIMIT 1") suspend fun workerCache(key: String): WorkerCacheEntity?
    @Query("SELECT * FROM worker_cache") suspend fun allWorkerCacheForRestore(): List<WorkerCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWorkerCache(rows: List<WorkerCacheEntity>)
    @Query("DELETE FROM worker_cache WHERE `key` = :key") suspend fun deleteWorkerCache(key: String)
    @Query("DELETE FROM worker_cache") suspend fun clearWorkerCache()

    @Query("DELETE FROM messages") suspend fun clearAllMessages()
    @Query("DELETE FROM alarms") suspend fun clearAllAlarms()
    @Query("DELETE FROM notes") suspend fun clearAllNotes()
    @Query("DELETE FROM notifications") suspend fun clearAllNotifications()
    @Query("DELETE FROM automation_rules") suspend fun clearAllAutomationRules()
    @Query("DELETE FROM macros") suspend fun clearAllMacros()
    @Query("DELETE FROM clipboard_history") suspend fun clearAllClipboardHistory()
    @Query("DELETE FROM worker_cache") suspend fun clearAllWorkerCache()
}

data class NotificationPackageCount(val packageName: String, val count: Int)
