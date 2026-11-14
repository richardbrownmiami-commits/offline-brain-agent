package com.cue.offlinebrain.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeEvidenceMarkupTest {
    @Test fun splitsMatchedTermsForTextHighlightingAndProducesCleanCopyText() {
        val marked = "Role: ⟦Senior Engineer⟧ at Acme; email alex@example.com"
        val parts = ResumeEvidenceMarkup.segments(marked)
        assertEquals(listOf("Role: ", "Senior Engineer", " at Acme; email alex@example.com"), parts.map { it.text })
        assertEquals(listOf(false, true, false), parts.map { it.highlighted })
        assertEquals("Role: Senior Engineer at Acme; email alex@example.com", ResumeEvidenceMarkup.plain(marked))
        assertFalse(ResumeEvidenceMarkup.plain(marked).contains('⟦'))
    }

    @Test fun leavesOrdinaryTextAndUnclosedMarkerSafe() {
        assertEquals(listOf(EvidenceTextSegment("No marks", false)), ResumeEvidenceMarkup.segments("No marks"))
        assertEquals("Before ⟦unfinished", ResumeEvidenceMarkup.plain("Before ⟦unfinished"))
        assertTrue(ResumeEvidenceMarkup.segments("Before ⟦unfinished").none { it.highlighted })
    }
}
