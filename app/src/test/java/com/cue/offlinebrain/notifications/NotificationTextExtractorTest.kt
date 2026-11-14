package com.cue.offlinebrain.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextExtractorTest {
    @Test fun handlesNullBlankZeroAndNegativeLimits() {
        assertEquals("", NotificationTextExtractor.safeTake(null, 10))
        assertEquals("", NotificationTextExtractor.safeTake("", 10))
        assertEquals("", NotificationTextExtractor.safeTake("sample", 0))
        assertEquals("", NotificationTextExtractor.safeTake("sample", -1))
    }

    @Test fun preservesValuesAtTheRequestedCharacterBoundaries() {
        listOf(1, 23, 180).forEach { size ->
            val input = "x".repeat(size)
            assertEquals("length $size", size, NotificationTextExtractor.safeTake(input, size).length)
            assertEquals("length $size stays unchanged", input, NotificationTextExtractor.safeTake(input, size))
        }
    }

    @Test fun capsLongInputsAtRequestedBoundary() {
        assertEquals(180, NotificationTextExtractor.safeTake("x".repeat(181), 180).length)
        assertEquals(3_000, NotificationTextExtractor.safeTake("x".repeat(5_000), 3_000).length)
        assertEquals("x".repeat(500), NotificationTextExtractor.safeTake("x".repeat(500), NotificationTextExtractor.TITLE_LIMIT))
    }

    @Test fun doesNotLeaveHalfOfASurrogatePair() {
        assertEquals("ab", NotificationTextExtractor.safeTake("ab\uD83D\uDE00cd", 3))
        assertEquals("ab\uD83D\uDE00", NotificationTextExtractor.safeTake("ab\uD83D\uDE00cd", 4))
    }
}
