package com.cue.offlinebrain.alarm

import org.junit.Assert.*
import org.junit.Test
import com.cue.offlinebrain.data.AlarmEntity
import java.time.LocalDateTime

class CronScheduleTest {
    @Test fun nextMinuteForStepExpression() {
        val next = CronSchedule.parse("*/15 * * * *").nextAfter(LocalDateTime.of(2025, 1, 2, 10, 7))
        assertEquals(LocalDateTime.of(2025, 1, 2, 10, 15), next)
    }
    @Test fun handlesRangesAndLists() {
        val schedule = CronSchedule.parse("0 8,17 * * 1-5")
        assertTrue(schedule.matches(LocalDateTime.of(2025, 1, 6, 8, 0)))
        assertFalse(schedule.matches(LocalDateTime.of(2025, 1, 5, 8, 0)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongFieldCount() {
        CronSchedule.parse("0 8 * *")
    }

    @Test fun stopAndSnoozeRemoveOneTimeAlarmsButKeepRepeatingAlarms() {
        val oneTime = AlarmEntity(label = "One time", triggerAt = 1000L)
        val repeating = AlarmEntity(label = "Weekly", triggerAt = 1000L, cron = "0 8 * * 1")
        assertTrue(AlarmDismissalPolicy.removeAfterStop(oneTime))
        assertTrue(AlarmDismissalPolicy.replaceAfterSnooze(oneTime))
        assertFalse(AlarmDismissalPolicy.removeAfterStop(repeating))
        assertFalse(AlarmDismissalPolicy.replaceAfterSnooze(repeating))
        assertFalse(AlarmDismissalPolicy.removeAfterStop(null))
    }
}
