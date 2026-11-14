package com.cue.offlinebrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseAndClipboardPolicyTest {
    @Test fun phraseRoutesOnlyWhenConfiguredPhraseOccursInText() {
        val phrases = listOf(LearnedPhrase("weather", "weather"), LearnedPhrase("headline please", "news"))
        assertEquals("weather", LearnedPhraseMatcher.firstMatch("What's the WEATHER in Delhi?", phrases)?.intent)
        assertEquals("news", LearnedPhraseMatcher.firstMatch("headline please", phrases)?.intent)
        assertNull(LearnedPhraseMatcher.firstMatch("rain tomorrow", phrases))
    }

    @Test fun clipboardPolicyDropsEmptyTextAndCapsStoredValue() {
        assertNull(ClipboardCapturePolicy.normalize(null))
        assertNull(ClipboardCapturePolicy.normalize("  \n"))
        assertEquals("hello", ClipboardCapturePolicy.normalize("  hello  "))
        assertEquals(4000, ClipboardCapturePolicy.normalize("x".repeat(6000))?.length)
        assertTrue(ClipboardCapturePolicy.normalize("  hello  ")!!.length <= 4000)
    }
}
