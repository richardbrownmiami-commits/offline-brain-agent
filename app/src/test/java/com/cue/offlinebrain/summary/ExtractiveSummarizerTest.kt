package com.cue.offlinebrain.summary

import org.junit.Assert.*
import org.junit.Test

class ExtractiveSummarizerTest {
    @Test fun summaryUsesVerbatimSourceSentences() {
        val source = "Earth orbits the Sun once each year. The oceans cover most of the planet. Plants use sunlight to make food. The Moon orbits Earth every month."
        val result = ExtractiveSummarizer().summarize(source, 2)
        assertTrue(source.contains(result.substringBeforeLast('.').trim()))
        assertFalse(result.contains("therefore", true))
    }
    @Test fun returnsShortSourceRatherThanInventingText() {
        assertEquals("Short text.", ExtractiveSummarizer().summarize("Short text."))
    }
}
