package com.cue.offlinebrain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotificationDiagnosticAggregatorTest {
    @Test fun repeatedSkipsAggregateByPackageMinuteAndFixedReason() {
        val aggregator = NotificationDiagnosticAggregator()
        repeat(7) { aggregator.record("com.example.app", NotificationDecisionReason.EMPTY_TEXT, 120_000L + it) }
        aggregator.record("com.example.app", NotificationDecisionReason.ONGOING, 120_010L)
        aggregator.record("com.example.other", NotificationDecisionReason.EMPTY_TEXT, 120_020L)

        val summaries = aggregator.drain()
        assertEquals(3, summaries.size)
        assertEquals(7L, summaries.single { it.packageName == "com.example.app" && it.reasonCode == "empty-text" }.count)
        assertEquals(1L, summaries.single { it.reasonCode == "ongoing" }.count)
        assertEquals(1L, summaries.single { it.packageName == "com.example.other" }.count)
        assertTrue(summaries.all { it.minute == 2L })
    }

    @Test fun nextMinuteEmitsOnlyOlderMinuteSummary() {
        val aggregator = NotificationDiagnosticAggregator()
        aggregator.record("com.example.app", NotificationDecisionReason.DUPLICATE, 59_999L)
        val expired = aggregator.record("com.example.app", NotificationDecisionReason.DUPLICATE, 60_000L)
        assertEquals(1, expired.size)
        assertEquals(1L, expired.single { it.minute == 0L }.count)
        val active = aggregator.drain()
        assertEquals(1, active.size)
        assertEquals(1L, active.single { it.minute == 1L }.count)
        assertTrue(aggregator.record("com.example.app", NotificationDecisionReason.EMPTY_TEXT, 60_001L).isEmpty())
    }

    @Test fun noisyUpdatesAreNeitherCountedNorEmitted() {
        val aggregator = NotificationDiagnosticAggregator()
        repeat(50) {
            assertTrue(aggregator.record("com.example.media", NotificationDecisionReason.ONGOING, it.toLong(), noisyUpdate = true).isEmpty())
        }
        assertEquals(0, aggregator.bucketCount())
        assertTrue(aggregator.drain().isEmpty())
    }

    @Test fun explicitDrainReturnsConciseSummariesAndClearsState() {
        val aggregator = NotificationDiagnosticAggregator()
        aggregator.record("com.example.app", NotificationDecisionReason.BLOCKED_APP, 180_000L)
        aggregator.record("com.example.app", NotificationDecisionReason.BLOCKED_APP, 180_001L)

        val summary = aggregator.drain().single()
        assertEquals(2L, summary.count)
        assertEquals("notification-skip package=com.example.app minute=3 reason=blocked-app count=2", summary.asLine())
        assertEquals(0, aggregator.bucketCount())
        assertTrue(aggregator.drain().isEmpty())
    }

    @Test fun concurrentRecordsRetainExactCount() {
        val aggregator = NotificationDiagnosticAggregator()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(1_000) { aggregator.record("com.example.concurrent", NotificationDecisionReason.RATE_LIMITED, 600_000L) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(8_000L, aggregator.drain().single().count)
    }

    @Test fun mapHasHardSizeLimitAndUsesOneDimensionFreeOverflowBucket() {
        val aggregator = NotificationDiagnosticAggregator(maxBuckets = 4)
        repeat(30) { index ->
            aggregator.record("com.example.app$index", NotificationDecisionReason.EMPTY_TEXT, index.toLong())
        }
        assertEquals(4, aggregator.bucketCount())
        val summaries = aggregator.drain()
        assertEquals(4, summaries.size)
        val overflow = summaries.single { it.packageName == "[overflow]" }
        assertEquals("overflow", overflow.reasonCode)
        assertEquals(27L, overflow.count)
        assertEquals(0, aggregator.bucketCount())
    }

    @Test fun packageFieldIsBoundedAndControlOrOtherCharactersCannotEnterOutput() {
        val aggregator = NotificationDiagnosticAggregator()
        val unsafe = "com.example.app\n\u0000/PRIVATE\t" + "x".repeat(200)
        aggregator.record(unsafe, NotificationDecisionReason.EXCEPTION, 0L)
        val summary = aggregator.drain().single()
        assertEquals(128, summary.packageName.length)
        assertTrue(summary.packageName.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' })
        assertTrue(summary.asLine().none { it.isISOControl() })
        assertEquals("unknown", sanitizeResult(aggregator, "\n\u0000..."))
    }

    private fun sanitizeResult(aggregator: NotificationDiagnosticAggregator, input: String): String {
        aggregator.record(input, NotificationDecisionReason.EMPTY_TEXT, 0L)
        return aggregator.drain().single().packageName
    }
}
