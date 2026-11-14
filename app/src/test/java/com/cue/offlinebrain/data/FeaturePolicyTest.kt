package com.cue.offlinebrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cue.offlinebrain.notifications.NotificationReplyRegistry
import com.cue.offlinebrain.notifications.NotificationBurstGuard
import com.cue.offlinebrain.notifications.NotificationCategory
import com.cue.offlinebrain.notifications.NotificationPrivacyFormatter
import com.cue.offlinebrain.worker.FeatureWorkPolicy

class FeaturePolicyTest {
    @Test fun notificationBlocklistWinsAndAllowlistIsOptional() {
        val open = AppSettings(notificationBlocklist = "com.secret.app")
        assertTrue(open.shouldStoreNotification("com.chat.app"))
        assertFalse(open.shouldStoreNotification("com.secret.app"))
        val restricted = AppSettings(notificationAllowlist = "com.chat.app\ncom.work.mail", notificationBlocklist = "com.chat.app.private")
        assertTrue(restricted.shouldStoreNotification("com.chat.app"))
        assertTrue(restricted.shouldStoreNotification("com.work.mail.mobile"))
        assertFalse(restricted.shouldStoreNotification("com.news.app"))
        assertFalse(restricted.shouldStoreNotification("com.chat.app.private"))
    }

    @Test fun macroParserDropsBlankStepsAndBoundsCommands() {
        val parsed = MacroStepParser.parse("  weather  \n\nnews\n" + "x".repeat(700))
        assertEquals(3, parsed.size)
        assertEquals("weather", parsed[0])
        assertEquals(500, parsed[2].length)
        assertTrue(MacroStepParser.parse("\n  \n").isEmpty())
    }

    @Test fun cacheKeysDoNotExposeApiHeadersAndFreshnessIsBounded() {
        val first = WorkerCacheKeys.customApi("https://example.invalid/status", "data.ok", mapOf("Authorization" to "Bearer secret-one"))
        val second = WorkerCacheKeys.customApi("https://example.invalid/status", "data.ok", mapOf("Authorization" to "Bearer secret-two"))
        assertFalse(first.contains("secret"))
        assertFalse(first == second)
        val now = 1_000_000L
        assertTrue(WorkerCachePolicy.isFresh(now - 1_000L, now))
        assertFalse(WorkerCachePolicy.isFresh(now - WorkerCachePolicy.MAX_AGE_MS - 1, now))
        assertFalse(WorkerCachePolicy.isFresh(now + 1_000, now))
    }

    @Test fun notificationReplyRequiresCurrentActiveAction() {
        NotificationReplyRegistry.clear()
        assertFalse(NotificationReplyRegistry.canReply("com.example.app", 1234L))
        assertEquals("com.example.app:1234", NotificationReplyRegistry.identity("com.example.app", 1234L))
    }

    @Test fun notificationCategoriesPreferCallTypeAndKnownPackages() {
        assertEquals("Chat", NotificationCategory.infer("com.whatsapp", null, -1))
        assertEquals("Calls", NotificationCategory.infer("com.whatsapp", android.app.Notification.CATEGORY_CALL, -1))
        assertEquals("Games", NotificationCategory.infer("com.example.game", null, android.content.pm.ApplicationInfo.CATEGORY_GAME))
        assertEquals("Other", NotificationCategory.normalized("Unknown group"))
    }

    @Test fun notificationBurstGuardDropsExactDuplicatesButAllowsUpdates() {
        val guard = NotificationBurstGuard(windowMillis = 1_000)
        assertTrue(guard.accept("key", "hash-a", 5_000))
        assertFalse(guard.accept("key", "hash-a", 5_500))
        assertTrue(guard.accept("key", "hash-b", 5_500))
        assertTrue(guard.accept("key", "hash-a", 6_001))
    }

    @Test fun defaultNotificationSummaryHidesCallAndSmsBodies() {
        assertEquals("Missed caller", NotificationPrivacyFormatter.line("Calls", "Missed caller", "private call details"))
        assertEquals("Sender", NotificationPrivacyFormatter.line("Messages (SMS)", "Sender", "private text"))
        assertEquals("Sender: private text", NotificationPrivacyFormatter.line("Messages (SMS)", "Sender", "private text", includeMessageBody = true))
    }

    @Test fun backgroundSchedulesRequireNetworkAndBatteryWithBackoff() {
        assertTrue(FeatureWorkPolicy.REQUIRE_NETWORK)
        assertTrue(FeatureWorkPolicy.REQUIRE_BATTERY_NOT_LOW)
        assertEquals(12L, FeatureWorkPolicy.REFRESH_INTERVAL_HOURS)
        assertEquals(24L, FeatureWorkPolicy.BACKUP_INTERVAL_HOURS)
        assertEquals(30L, FeatureWorkPolicy.BACKOFF_SECONDS)
    }
}
