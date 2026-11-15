package com.cue.offlinebrain.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationAlert(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val category: String,
    val groupName: String,
    val postedAt: Long
)

object NotificationAlertPolicy {
    fun shouldPublish(mutedApp: Boolean, mutedCategory: Boolean, ongoing: Boolean, media: Boolean): Boolean =
        !mutedApp && !mutedCategory && !ongoing && !media
}

/** Foreground-only alert queue; the capture database remains the source of truth. */
object NotificationAlertQueue {
    private val lock = Any()
    private val _pending = MutableStateFlow<List<NotificationAlert>>(emptyList())
    val pending: StateFlow<List<NotificationAlert>> = _pending.asStateFlow()

    fun enqueue(alert: NotificationAlert) = synchronized(lock) {
        _pending.value = (_pending.value.filterNot { it.id == alert.id } + alert).takeLast(3)
    }

    fun dismiss(id: String) = synchronized(lock) {
        _pending.value = _pending.value.filterNot { it.id == id }
    }

    fun clear() = synchronized(lock) { _pending.value = emptyList() }
}
