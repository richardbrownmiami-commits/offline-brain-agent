package com.cue.offlinebrain.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextCleanupTest {
    private val sample = """
        FIELD STUDY REPORT
        Page 1 of 2
        1. Findings
        The study found that careful planning improved the amount of retriev-
        able information, even when the document contained hard-wrapped lines.
        Researchers checked every result against its original source sentence.
        The cleaned record preserves headings and keeps each selected sentence in order.
        Most participants said that the shorter summary was easier to read on a phone.
        • First key point remains visible
          when the PDF wraps it to another line.
        1
        \u000cFIELD STUDY REPORT
        Page 2 of 2
        2. Next steps
        Teams should keep source documents private and review the summary before sharing it.
        A second reviewer should confirm that important dates and names remain accurate.
        999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999.
        FIELD STUDY REPORT
    """.trimIndent().replace("\\u000c", "\u000c")

    @Test fun removesRepeatedHeadersAndPageNumbers() {
        val clean = PdfTextCleanup.clean(sample)
        assertFalse(clean.contains("Page 1 of 2"))
        assertFalse(clean.contains("Page 2 of 2"))
        assertFalse("Unexpected numeric noise in: $clean", clean.contains("999999999999999999999"))
        assertTrue(clean.contains("Researchers checked every result"))
    }

    @Test fun joinsEndOfLineHyphenationWithoutAddingAWordBreak() {
        val clean = PdfTextCleanup.clean(sample)
        assertTrue("Hyphenated word was not repaired in: $clean", clean.contains("retrievable information"))
        assertFalse(clean.contains("retriev- able"))
    }

    @Test fun joinsHardWrappedParagraphsAndBulletContinuations() {
        val clean = PdfTextCleanup.clean(sample)
        assertTrue(clean.contains("hard-wrapped lines. Researchers checked"))
        assertTrue(clean.contains("• First key point remains visible when the PDF wraps it to another line."))
    }

    @Test fun reportHasExtractiveSummaryPointsAndSections() {
        val report = PdfTextCleanup.report(sample, "Field study")
        assertEquals("Field study", report.title)
        assertEquals("1. Findings", PdfTextCleanup.report(sample).title)
        assertTrue("Unexpected summary/key points: ${report.summary} / ${report.keyPoints}; sections: ${report.sections}", report.summary.size in 3..5)
        assertTrue("Unexpected key-point count: ${report.keyPoints}", report.keyPoints.size in 0..8)
        assertTrue("Summary sentences must not repeat as key points", report.summary.none { it in report.keyPoints })
        assertTrue("Key points must remain one line each", report.keyPoints.all { '\n' !in it })
        assertTrue(report.sections.any { it.contains("Findings") })
        assertTrue(report.sections.any { it.contains("Next steps") })
        assertTrue(report.toChatText().contains("extractive", ignoreCase = true))
        assertTrue(report.cleanedText.contains("retrievable information"))
    }

    @Test fun emptyOrSymbolOnlyTextProducesNoInventedSentences() {
        val report = PdfTextCleanup.report(" \n  1234567890  \n *** --- ")
        assertTrue(report.summary.isEmpty())
        assertTrue(report.keyPoints.isEmpty())
    }

    @Test fun resumePersonNameWinsOverPdfMetadataTitle() {
        val source = """Alex Morgan
            alex@example.com
            SKILLS
            Kotlin, Java
            EXPERIENCE
            Engineer | Acme | Remote | 2020 - Present
            EDUCATION
            BSc Computer Science""".trimIndent()
        assertEquals("Alex Morgan", PdfTextCleanup.report(source, "resume-file.pdf").title)
        assertFalse(PdfTextCleanup.report(source, "(anonymous)").title.equals("(anonymous)", true))
    }
}
