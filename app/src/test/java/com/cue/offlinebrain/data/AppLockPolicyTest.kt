package com.cue.offlinebrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    private class FakeElapsedClock(var now: Long = 10_000L) {
        fun advance(millis: Long) { now += millis }
    }

    @Test fun coldStartAlwaysRequiresUnlockWhenCalledForAnEnabledLock() {
        assertTrue(AppLockPolicy.shouldRelock(120, null))
        assertTrue(AppLockPolicy.shouldRelockAt(120, null, 10_000L))
    }

    @Test fun exactlySixRequestedDelaysAreSupportedAndDefaultIsTwoMinutes() {
        assertEquals(listOf(0, 30, 60, 120, 300, 900), AppLockPolicy.allowedTimeoutSeconds)
        assertEquals(120, AppLockPolicy.DEFAULT_TIMEOUT_SECONDS)
        assertEquals(0L, AppLockPolicy.timeoutMillis(0))
        assertEquals(30_000L, AppLockPolicy.timeoutMillis(30))
        assertEquals(60_000L, AppLockPolicy.timeoutMillis(60))
        assertEquals(120_000L, AppLockPolicy.timeoutMillis(120))
        assertEquals(300_000L, AppLockPolicy.timeoutMillis(300))
        assertEquals(900_000L, AppLockPolicy.timeoutMillis(900))
    }

    @Test fun shortRecentsOrAppSwitchDoesNotLockOnReturnForTheDefaultDelay() {
        val clock = FakeElapsedClock()
        val leftForegroundAt = clock.now
        clock.advance(700L)
        assertFalse(AppLockPolicy.shouldRelockAt(120, leftForegroundAt, clock.now))
        clock.advance(30_000L)
        assertFalse(AppLockPolicy.shouldRelockAt(120, leftForegroundAt, clock.now))
    }

    @Test fun defaultDelayLocksAtExactlyTwoMinutesButNotBefore() {
        val clock = FakeElapsedClock()
        val backgroundAt = clock.now
        clock.advance(119_999L)
        assertFalse(AppLockPolicy.shouldRelockAt(120, backgroundAt, clock.now))
        clock.advance(1L)
        assertTrue(AppLockPolicy.shouldRelockAt(120, backgroundAt, clock.now))
    }

    @Test fun immediateAndFifteenMinuteBoundaryAreRespected() {
        assertTrue(AppLockPolicy.shouldRelockAt(0, 10L, 10L))
        val clock = FakeElapsedClock()
        val backgroundAt = clock.now
        clock.advance(899_999L)
        assertFalse(AppLockPolicy.shouldRelockAt(900, backgroundAt, clock.now))
        clock.advance(1L)
        assertTrue(AppLockPolicy.shouldRelockAt(900, backgroundAt, clock.now))
        assertEquals(11_500L, AppLockPolicy.trueBackgroundAt(12_000L, 11_500L))
        assertEquals(12_000L, AppLockPolicy.trueBackgroundAt(12_000L, 9_000L))
        assertEquals(12_000L, AppLockPolicy.trueBackgroundAt(12_000L, 12_001L))
    }

    @Test fun invalidPersistedTimeoutFailsToSafeTwoMinuteDefault() {
        assertEquals(120_000L, AppLockPolicy.timeoutMillis(-1))
        assertEquals(120_000L, AppLockPolicy.timeoutMillis(600))
        assertEquals(120, AppLockPolicy.normalizeTimeoutSeconds(5))
        assertTrue(AppLockPolicy.shouldRelockAt(120, 10_000L, 130_000L))
    }

    @Test fun negativeElapsedOrClockRollbackFailsClosed() {
        assertTrue(AppLockPolicy.shouldRelock(30, -1L))
        assertTrue(AppLockPolicy.shouldRelockAt(30, 20_000L, 19_999L))
    }
}
