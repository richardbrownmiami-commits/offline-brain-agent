package com.cue.offlinebrain.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechRetryPolicyTest {
    @Test fun retriesBusyOrClientExactlyOnce() {
        assertTrue(SpeechRetryPolicy.shouldRetry(8, false, false))
        assertTrue(SpeechRetryPolicy.shouldRetry(5, false, false))
        assertFalse(SpeechRetryPolicy.shouldRetry(8, true, false))
        assertFalse(SpeechRetryPolicy.shouldRetry(5, true, false))
        assertEquals(400L, SpeechRetryPolicy.RETRY_DELAY_MS)
    }
    @Test fun cancellationInvalidatesRetry() {
        assertFalse(SpeechRetryPolicy.shouldRetry(8, false, true))
        assertFalse(SpeechRetryPolicy.shouldRetry(5, false, true))
    }
    @Test fun onlyAutoModeFallsBackAfterRetryFailure() {
        assertTrue(SpeechRetryPolicy.shouldFallbackToVosk(VoiceEngineMode.AUTO, true))
        assertFalse(SpeechRetryPolicy.shouldFallbackToVosk(VoiceEngineMode.ANDROID, true))
        assertFalse(SpeechRetryPolicy.shouldFallbackToVosk(VoiceEngineMode.OFFLINE, true))
        VoiceEngineMode.values().forEach { assertFalse(SpeechRetryPolicy.shouldFallbackToVosk(it, false)) }
    }
    @Test fun serviceComparatorPlacesGoogleFirstAndOrdersRestDeterministically() {
        val items = listOf("z.pkg", "com.example", RecognitionServiceOrder.GOOGLE_PACKAGE, "a.pkg")
        assertEquals(listOf(RecognitionServiceOrder.GOOGLE_PACKAGE, "a.pkg", "com.example", "z.pkg"), items.sortedWith(RecognitionServiceOrder.comparator))
    }
    @Test fun diagnosticsKeepLastFiveInOrderAndSanitizeTranscript() {
        var events = emptyList<SpeechDiagnosticEvent>()
        (1..7).forEach { events = SpeechEventRing.append(events, SpeechDiagnosticEvent("ANDROID", it, "event $it transcript: private words")) }
        assertEquals(5, events.size)
        assertEquals(listOf(3, 4, 5, 6, 7), events.map { it.errorCode })
        assertEquals("event 7 speech text redacted", events.last().status)
        assertFalse(events.joinToString { it.status }.contains("private words"))
    }
}
