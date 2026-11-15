package com.cue.offlinebrain.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.DateFormat
import java.util.Date

/** Structured, versioned content returned by the local request router. */
sealed interface BrainReply {
    val plainText: String

    data class Text(val text: String) : BrainReply {
        override val plainText: String get() = text
    }

    data class DeviceStatusCard(
        val title: String,
        val rows: List<DeviceStatusRow>,
        val note: String? = null
    ) : BrainReply {
        override val plainText: String get() = buildString {
            append(title)
            rows.forEach { append('\n').append(it.label).append(": ").append(it.value) }
            note?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
        }
    }

    data class NotificationSummaryCard(
        val title: String,
        val category: String?,
        val count: Int,
        val unreadCount: Int,
        val latestAt: Long?,
        val categoryCounts: List<ReplyCategoryCount> = emptyList(),
        val note: String? = null
    ) : BrainReply {
        override val plainText: String get() = buildString {
            append(title).append(" (").append(count).append(if (count == 1) " saved item" else " saved items").append(")")
            if (categoryCounts.isNotEmpty()) append(": ").append(categoryCounts.joinToString(" · ") { "${it.category} ${it.count}" })
            if (latestAt != null) append("\nLatest: ").append(formatReplyTime(latestAt))
            note?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
        }
    }

    data class NotificationList(
        val title: String,
        val category: String?,
        val totalCount: Int,
        val unreadCount: Int,
        val latestAt: Long?,
        val categoryCounts: List<ReplyCategoryCount>,
        val items: List<ReplyNotificationItem>,
        val note: String? = null,
        val readAloudRequested: Boolean = false
    ) : BrainReply {
        override val plainText: String get() = buildString {
            append(title).append(" (").append(totalCount).append(")")
            if (categoryCounts.isNotEmpty()) append(" · ").append(categoryCounts.joinToString(" · ") { "${it.category} ${it.count}" })
            items.forEach { item ->
                append('\n').append("• ").append(item.title.ifBlank { item.packageName.substringAfterLast('.') })
                if (item.duplicateCount > 1) append(" x").append(item.duplicateCount)
                if (item.text.isNotBlank() && item.category !in setOf("Calls", "Messages/SMS", "Messages (SMS)")) append(" — ").append(item.text)
                append(" · ").append(formatReplyTime(item.postedAt))
            }
            note?.takeIf(String::isNotBlank)?.let { append("\n\n").append(it) }
        }
    }

    data class NotificationDeleteConfirmation(
        val ids: List<Long>,
        val category: String?,
        val range: String,
        val createdAt: Long,
        val totalCount: Int = ids.size
    ) : BrainReply {
        override val plainText: String get() = "Delete ${ids.size} saved notification${if (ids.size == 1) "" else "s"}${if (totalCount > ids.size) " (of $totalCount matching)" else ""}${category?.let { " in $it" }.orEmpty()} from $range?"
    }

    data class NotificationReplyConfirmation(
        val notificationId: Long,
        val packageName: String,
        val postedAt: Long,
        val target: String,
        val text: String
    ) : BrainReply {
        override val plainText: String get() = "Reply to $target: $text"
    }

    data class DocumentSummary(
        val title: String,
        val summary: String,
        val cleanedText: String,
        val sourceName: String? = null
    ) : BrainReply {
        override val plainText: String get() = summary
    }
}

data class DeviceStatusRow(val label: String, val value: String, val progressPercent: Int? = null)
data class ReplyCategoryCount(val category: String, val count: Int)
data class ReplyNotificationItem(
    val id: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val category: String,
    val postedAt: Long,
    val groupName: String = "",
    val duplicateCount: Int = 1,
    val isUnread: Boolean = true
)

fun brainReplyFromLegacyText(value: String, sourceName: String? = null): BrainReply {
    val marker = "\n--- CLEANED TEXT ---\n"
    if (marker !in value) return BrainReply.Text(value)
    val summary = value.substringBefore(marker).trim()
    val cleaned = value.substringAfter(marker).trim()
    val title = summary.lineSequence().firstOrNull()?.takeIf(String::isNotBlank) ?: "Document summary"
    return BrainReply.DocumentSummary(title, summary, cleaned, sourceName)
}

/** JSON contract for persisted assistant replies. Unknown versions/types safely fall back to the legacy text column. */
object BrainReplyCodec {
    const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(reply: BrainReply): String = buildJsonObject {
        put("version", JsonPrimitive(VERSION))
        when (reply) {
            is BrainReply.Text -> {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(reply.text))
            }
            is BrainReply.DeviceStatusCard -> {
                put("type", JsonPrimitive("device_status_card"))
                put("title", JsonPrimitive(reply.title))
                put("rows", buildJsonArray { reply.rows.forEach { add(statusRowJson(it)) } })
                put("note", reply.note?.let(::JsonPrimitive) ?: JsonNull)
            }
            is BrainReply.NotificationSummaryCard -> {
                put("type", JsonPrimitive("notification_summary_card"))
                put("title", JsonPrimitive(reply.title))
                put("category", reply.category?.let(::JsonPrimitive) ?: JsonNull)
                put("count", JsonPrimitive(reply.count))
                put("unreadCount", JsonPrimitive(reply.unreadCount))
                put("latestAt", reply.latestAt?.let(::JsonPrimitive) ?: JsonNull)
                put("categoryCounts", categoryCountsJson(reply.categoryCounts))
                put("note", reply.note?.let(::JsonPrimitive) ?: JsonNull)
            }
            is BrainReply.NotificationList -> {
                put("type", JsonPrimitive("notification_list"))
                put("title", JsonPrimitive(reply.title))
                put("category", reply.category?.let(::JsonPrimitive) ?: JsonNull)
                put("totalCount", JsonPrimitive(reply.totalCount))
                put("unreadCount", JsonPrimitive(reply.unreadCount))
                put("latestAt", reply.latestAt?.let(::JsonPrimitive) ?: JsonNull)
                put("categoryCounts", categoryCountsJson(reply.categoryCounts))
                put("items", buildJsonArray { reply.items.forEach { add(notificationItemJson(it)) } })
                put("note", reply.note?.let(::JsonPrimitive) ?: JsonNull)
                put("readAloudRequested", JsonPrimitive(reply.readAloudRequested))
            }
            is BrainReply.NotificationDeleteConfirmation -> {
                put("type", JsonPrimitive("notification_delete_confirmation"))
                put("ids", buildJsonArray { reply.ids.forEach { add(JsonPrimitive(it)) } })
                put("category", reply.category?.let(::JsonPrimitive) ?: JsonNull)
                put("range", JsonPrimitive(reply.range))
                put("createdAt", JsonPrimitive(reply.createdAt))
                put("totalCount", JsonPrimitive(reply.totalCount))
            }
            is BrainReply.NotificationReplyConfirmation -> {
                put("type", JsonPrimitive("notification_reply_confirmation"))
                put("notificationId", JsonPrimitive(reply.notificationId))
                put("packageName", JsonPrimitive(reply.packageName))
                put("postedAt", JsonPrimitive(reply.postedAt))
                put("target", JsonPrimitive(reply.target))
                put("text", JsonPrimitive(reply.text))
            }
            is BrainReply.DocumentSummary -> {
                put("type", JsonPrimitive("document_summary"))
                put("title", JsonPrimitive(reply.title))
                put("summary", JsonPrimitive(reply.summary))
                put("cleanedText", JsonPrimitive(reply.cleanedText))
                put("sourceName", reply.sourceName?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }.toString()

    fun decode(value: String?): BrainReply? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(value).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return null
            val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
            fun text(key: String, fallback: String = ""): String = root[key]?.jsonPrimitive?.contentOrNull ?: fallback
            fun int(key: String, fallback: Int = 0): Int = root[key]?.jsonPrimitive?.intOrNull ?: fallback
            fun longOrNull(key: String): Long? = root[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
            fun stringOrNull(key: String): String? = root[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            fun note(): String? = stringOrNull("note")
            fun categoryCounts(): List<ReplyCategoryCount> = decodeCategoryCounts(root["categoryCounts"])
            when (type) {
                "text" -> BrainReply.Text(text("text"))
                "device_status_card" -> BrainReply.DeviceStatusCard(text("title"), decodeStatusRows(root["rows"]), note())
                "notification_summary_card" -> BrainReply.NotificationSummaryCard(
                    text("title"), stringOrNull("category"), int("count"), int("unreadCount"), longOrNull("latestAt"), categoryCounts(), note()
                )
                "notification_list" -> BrainReply.NotificationList(
                    text("title"), stringOrNull("category"), int("totalCount"), int("unreadCount"), longOrNull("latestAt"),
                    categoryCounts(), decodeNotificationItems(root["items"]), note(), root["readAloudRequested"]?.jsonPrimitive?.booleanOrNull ?: false
                )
                "notification_delete_confirmation" -> BrainReply.NotificationDeleteConfirmation(
                    (root["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty(),
                    stringOrNull("category"), text("range"), longOrNull("createdAt") ?: 0L, int("totalCount")
                )
                "notification_reply_confirmation" -> BrainReply.NotificationReplyConfirmation(
                    longOrNull("notificationId") ?: return null, text("packageName"), longOrNull("postedAt") ?: 0L,
                    text("target"), text("text")
                )
                "document_summary" -> BrainReply.DocumentSummary(text("title"), text("summary"), text("cleanedText"), stringOrNull("sourceName"))
                else -> null
            }
        }.getOrNull()
    }

    private fun statusRowJson(row: DeviceStatusRow): JsonObject = buildJsonObject {
        put("label", JsonPrimitive(row.label)); put("value", JsonPrimitive(row.value))
        put("progressPercent", row.progressPercent?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun categoryCountsJson(counts: List<ReplyCategoryCount>): JsonArray = buildJsonArray {
        counts.forEach { add(buildJsonObject { put("category", JsonPrimitive(it.category)); put("count", JsonPrimitive(it.count)) }) }
    }

    private fun notificationItemJson(item: ReplyNotificationItem): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(item.id)); put("packageName", JsonPrimitive(item.packageName)); put("title", JsonPrimitive(item.title))
        put("text", JsonPrimitive(item.text)); put("category", JsonPrimitive(item.category)); put("postedAt", JsonPrimitive(item.postedAt))
        put("groupName", JsonPrimitive(item.groupName)); put("duplicateCount", JsonPrimitive(item.duplicateCount)); put("isUnread", JsonPrimitive(item.isUnread))
    }

    private fun decodeStatusRows(element: JsonElement?): List<DeviceStatusRow> = (element as? JsonArray)?.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        DeviceStatusRow(
            obj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
            obj["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
            obj["progressPercent"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull
        )
    }.orEmpty()

    private fun decodeCategoryCounts(element: JsonElement?): List<ReplyCategoryCount> = (element as? JsonArray)?.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        ReplyCategoryCount(category, obj["count"]?.jsonPrimitive?.intOrNull ?: 0)
    }.orEmpty()

    private fun decodeNotificationItems(element: JsonElement?): List<ReplyNotificationItem> = (element as? JsonArray)?.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        ReplyNotificationItem(
            id = id,
            packageName = packageName,
            title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            category = obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            postedAt = obj["postedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            groupName = obj["groupName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            duplicateCount = obj["duplicateCount"]?.jsonPrimitive?.intOrNull ?: 1,
            isUnread = obj["isUnread"]?.jsonPrimitive?.booleanOrNull ?: !(obj["isRead"]?.jsonPrimitive?.booleanOrNull ?: false)
        )
    }.orEmpty()
}

private fun formatReplyTime(atMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(atMillis))
