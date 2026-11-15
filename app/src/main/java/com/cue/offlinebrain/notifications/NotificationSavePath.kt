package com.cue.offlinebrain.notifications

import com.cue.offlinebrain.data.BrainDao
import com.cue.offlinebrain.data.NotificationEntity

/** The shared database path used by live listener callbacks and the synthetic diagnostics check. */
object NotificationSavePath {
    suspend fun save(
        dao: BrainDao,
        row: NotificationEntity,
        retentionDays: Int? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val insertedId = dao.saveNotification(row)
        retentionDays?.let { days ->
            val retention = days.coerceIn(1, 365).toLong()
            val cutoff = nowMillis - retention * MILLIS_PER_DAY
            dao.deleteNotificationsBefore(cutoff)
        }
        return insertedId
    }

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
}
