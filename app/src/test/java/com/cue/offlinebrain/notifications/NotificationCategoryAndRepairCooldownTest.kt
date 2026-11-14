package com.cue.offlinebrain.notifications

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCategoryAndRepairCooldownTest {
    @Test
    fun callAndSystemPackageMappingsAreCaseInsensitive() {
        val expected = mapOf(
            "com.android.server.telecom" to "Calls",
            "com.android.phone" to "Calls",
            "com.android.incallui" to "Calls",
            "com.android.dialer" to "Calls",
            "com.google.android.dialer" to "Calls",
            "com.xiaomi.mipicks" to "System", // GetApps
            "com.xiaomi.discover" to "System",
            "com.miui.discover" to "System",
            "com.miui.personalassistant" to "System"
        )
        expected.forEach { (packageName, category) ->
            assertEquals(packageName, category, NotificationCategory.infer(packageName, null, ApplicationInfo.CATEGORY_UNDEFINED))
            assertEquals(packageName.uppercase(), category, NotificationCategory.infer(packageName.uppercase(), null, ApplicationInfo.CATEGORY_UNDEFINED))
        }
    }

    @Test
    fun callAliasesDoNotMatchNeighboringPrefixes() {
        assertEquals("Other", NotificationCategory.infer("com.xiaomishare.app", null, ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("Other", NotificationCategory.infer("com.miuiish.app", null, ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("System", NotificationCategory.infer("com.android.server.telecomx", null, ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("System", NotificationCategory.infer("com.android.phoney", null, ApplicationInfo.CATEGORY_UNDEFINED))
    }

    @Test
    fun repairCooldownBlocksJustBeforeBoundaryAndAllowsAtOrAfterBoundary() {
        val cooldown = RepairCooldown(60_000L)
        assertTrue(cooldown.tryAcquire(1_000L))
        assertFalse(cooldown.tryAcquire(60_999L)) // 59,999 ms
        assertTrue(cooldown.tryAcquire(61_000L)) // exactly 60,000 ms from accepted attempt
        assertTrue(cooldown.tryAcquire(121_001L)) // 60,001 ms
    }

    @Test
    fun simultaneousCooldownAcquisitionsCannotBothPass() {
        val cooldown = RepairCooldown(60_000L)
        val decisions = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (0 until 8).map {
            Thread { decisions += cooldown.tryAcquire(10_000L) }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(1, decisions.count { it })
    }
}
