package com.cue.offlinebrain.notifications

import android.content.Context

/** Per-category last-viewed marker stored locally, without notification content. */
object NotificationLastSeenStore {
    private const val PREFS = "notification_last_seen"
    private const val PREFIX = "category_"

    fun lastSeen(context: Context, category: String?): Long = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key(category), 0L).coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun markSeen(context: Context, category: String?, atMillis: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key(category), atMillis.coerceAtLeast(0L)).apply()
    }

    private fun key(category: String?): String {
        val safe = category.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48)
        return PREFIX + safe.ifBlank { "all" }
    }
}
