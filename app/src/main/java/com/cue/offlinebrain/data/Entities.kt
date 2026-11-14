package com.cue.offlinebrain.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("timestamp")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val triggerAt: Long,
    val cron: String? = null,
    val enabled: Boolean = true
)

@Entity(tableName = "notes", indices = [Index("updatedAt")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val done: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notifications", indices = [Index("postedAt"), Index("packageName"), Index("category"), Index(value = ["dedupeKey", "textHash"], unique = true)])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    @ColumnInfo(defaultValue = "''") val dedupeKey: String = "",
    @ColumnInfo(defaultValue = "''") val textHash: String = "",
    @ColumnInfo(defaultValue = "'Other'") val category: String = "Other",
    @ColumnInfo(defaultValue = "0") val isOngoing: Boolean = false,
    @ColumnInfo(defaultValue = "''") val groupName: String = "",
    @ColumnInfo(defaultValue = "0") val isRead: Boolean = false
)

data class NotificationCategoryCount(val category: String, val count: Int)

@Fts4
@Entity(tableName = "faqs")
data class FaqEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val question: String,
    val answer: String,
    val phrases: String = ""
)

@Entity(tableName = "automation_rules", indices = [Index("triggerPhrase")])
data class AutomationRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val actionText: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "macros", indices = [Index("updatedAt")])
data class MacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** One explicitly entered, user-reviewable command per line. */
    val steps: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "clipboard_history", indices = [Index("copiedAt")])
data class ClipboardEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val copiedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "worker_cache")
data class WorkerCacheEntity(
    @PrimaryKey val key: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)
