package com.cue.offlinebrain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlertQueueTest {
    private fun alert(id: String) = NotificationAlert(id, "com.example.app", "Sender", "Preview", "Chat", "", id.hashCode().toLong())

    @Test fun queueKeepsOnlyTheNewestThreeAlerts() {
        NotificationAlertQueue.clear()
        listOf("one", "two", "three", "four").forEach { NotificationAlertQueue.enqueue(alert(it)) }
        assertEquals(listOf("two", "three", "four"), NotificationAlertQueue.pending.value.map { it.id })
        NotificationAlertQueue.clear()
    }

    @Test fun dismissAndClearRemoveQueuedAlerts() {
        NotificationAlertQueue.clear()
        NotificationAlertQueue.enqueue(alert("a"))
        NotificationAlertQueue.enqueue(alert("b"))
        NotificationAlertQueue.dismiss("a")
        assertEquals(listOf("b"), NotificationAlertQueue.pending.value.map { it.id })
        NotificationAlertQueue.clear()
        assertTrue(NotificationAlertQueue.pending.value.isEmpty())
    }

    @Test fun mutedMediaAndOngoingAlertsAreSuppressedButNormalItemsPass() {
        assertTrue(NotificationAlertPolicy.shouldPublish(false, false, false, false))
        assertFalse(NotificationAlertPolicy.shouldPublish(true, false, false, false))
        assertFalse(NotificationAlertPolicy.shouldPublish(false, true, false, false))
        assertFalse(NotificationAlertPolicy.shouldPublish(false, false, true, false))
        assertFalse(NotificationAlertPolicy.shouldPublish(false, false, false, true))
    }
}
