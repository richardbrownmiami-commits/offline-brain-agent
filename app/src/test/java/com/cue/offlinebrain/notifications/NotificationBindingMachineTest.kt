package com.cue.offlinebrain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBindingMachineTest {
    @Test fun stateTransitionsAreUnboundConnectingActive() {
        val machine = NotificationBindingMachine()
        assertEquals(NotificationBindingMachine.State.UNBOUND, machine.snapshot().state)
        assertTrue(machine.requestRebind())
        assertEquals(NotificationBindingMachine.State.CONNECTING, machine.snapshot().state)
        machine.connected(100L)
        assertEquals(NotificationBindingMachine.State.ACTIVE, machine.snapshot().state)
        assertEquals(100L, machine.snapshot().lastConnectAt)
    }

    @Test fun noRebindLoopWhileConnectingOrActive() {
        val machine = NotificationBindingMachine()
        assertTrue(machine.requestRebind())
        assertFalse(machine.requestRebind())
        machine.connected(1L)
        assertFalse(machine.requestRebind())
        assertEquals(1L, machine.snapshot().rebindRequests)
    }

    @Test fun backgroundUnbindAndThirtySecondIdleAreRecordedWithReasons() {
        val machine = NotificationBindingMachine()
        machine.requestRebind()
        machine.connected(1_000L)
        machine.notificationPosted(2_000L)
        assertNull(machine.unbindReason(31_999L, appForeground = false, alwaysOn = false))
        assertEquals(NotificationBindingMachine.UnbindReason.IDLE_30_SECONDS,
            machine.unbindReason(32_000L, appForeground = false, alwaysOn = false))
        assertEquals(NotificationBindingMachine.UnbindReason.APP_BACKGROUND,
            machine.appBackgrounded(alwaysOn = false))
        assertNull(machine.appBackgrounded(alwaysOn = true))
        machine.unbound(32_000L, NotificationBindingMachine.UnbindReason.IDLE_30_SECONDS)
        assertEquals(1L, machine.snapshot().unbindCount)
        assertEquals(NotificationBindingMachine.UnbindReason.IDLE_30_SECONDS, machine.snapshot().lastUnbindReason)
        assertEquals(30L, machine.snapshot().secondsIdle(32_000L))
    }

    @Test fun foregroundAndAlwaysOnPreventIdleUnbindAndPostsResetTheClock() {
        val machine = NotificationBindingMachine()
        machine.requestRebind()
        machine.connected(1_000L)
        assertNull(machine.unbindReason(90_000L, appForeground = true, alwaysOn = false))
        assertNull(machine.unbindReason(90_000L, appForeground = false, alwaysOn = true))
        machine.notificationPosted(90_000L)
        assertNull(machine.unbindReason(119_999L, appForeground = false, alwaysOn = false))
        assertEquals(NotificationBindingMachine.UnbindReason.IDLE_30_SECONDS,
            machine.unbindReason(120_000L, appForeground = false, alwaysOn = false))
        assertEquals(1L, machine.snapshot().notificationCount)
    }

    @Test fun failedConnectCanBeRequestedAgain() {
        val machine = NotificationBindingMachine()
        assertTrue(machine.requestRebind())
        machine.connectionFailed()
        assertEquals(NotificationBindingMachine.State.UNBOUND, machine.snapshot().state)
        assertTrue(machine.requestRebind())
        assertEquals(2L, machine.snapshot().rebindRequests)
    }

    @Test fun rebindWaitTimesOutAtThreeSecondsButNotBefore() {
        assertFalse(NotificationRebindWaitPolicy.timedOut(5_000L, 7_999L))
        assertTrue(NotificationRebindWaitPolicy.timedOut(5_000L, 8_000L))
    }
}
