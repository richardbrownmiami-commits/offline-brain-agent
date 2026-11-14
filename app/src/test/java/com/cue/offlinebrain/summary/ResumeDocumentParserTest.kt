package com.cue.offlinebrain.summary

import org.junit.Assert.*
import org.junit.Test

class ResumeDocumentParserTest {
    private val parser = ResumeDocumentParser()
    private val fixture = """Alex Morgan
alex.morgan@example.com | +1 415 555 0182
SUMMARY
Software engineer with experience building reliable payment platforms.
SKILLS
Kotlin, Java, SQL, Android
EXPERIENCE
Senior Engineer | Tech Mahindra | Pune | 2021 - Present
- Built payment services using Kotlin and SQL.
- Mentored a team delivering Android applications.
Engineer | Acme Systems | Mumbai | 2018 - 2021 | Consultant | Globex | Remote | 2016 - 2018
- Designed Java APIs for customer onboarding.
EDUCATION
BSc Computer Science, Example University, 2018
LANGUAGES
English, Hindi
"""

    @Test fun parsesResumeSectionsAndConcatenatedJobRecordsWithEmployerBullets() {
        val parsed = parser.parse(fixture)
        assertEquals(DocumentKind.RESUME, parsed.kind)
        assertEquals("Alex Morgan", parsed.name)
        assertTrue(parsed.contact.any { it.contains("alex.morgan@example.com") })
        assertTrue(parsed.summary.single().contains("payment platforms"))
        assertTrue(parsed.skills.containsAll(listOf("Kotlin", "Java", "SQL", "Android")))
        assertEquals(3, parsed.jobs.size)
        assertEquals("Tech Mahindra", parsed.jobs[0].company)
        assertEquals("Acme Systems", parsed.jobs[1].company)
        assertTrue(parsed.jobs[0].bullets.any { it.contains("payment services") })
        assertTrue(parsed.jobs[0].bullets.any { it.contains("Mentored") })
        assertTrue(parsed.jobs[2].bullets.any { it.contains("Java APIs") })
        assertTrue(parsed.education.isNotEmpty())
        assertTrue(parsed.languages.containsAll(listOf("English", "Hindi")))
    }

    @Test fun tenDeterministicQueriesReturnRelevantEvidenceOrExplicitAbsence() {
        data class Case(val query: String, val mustContain: String?, val found: Boolean)
        val cases = listOf(
            Case("What did Alex do at Tech Mahindra?", "Tech Mahindra", true),
            Case("What did Alex do at Acme Systems?", "Acme Systems", true),
            Case("What did Alex build using Kotlin?", "Kotlin", true),
            Case("What skills does Alex have? Kotlin", "Kotlin", true),
            Case("What is the summary about payment platforms?", "payment platforms", true),
            Case("What is Alex Morgan's name and email?", "alex.morgan@example.com", true),
            Case("What did Alex do at Globex?", "Globex", true),
            Case("Does Alex know Rust?", null, false),
            Case("When did Alex work at Tech Mahindra?", "2021 - Present", true),
            Case("What language does Alex speak: Hindi?", "Hindi", true)
        )
        cases.forEach { case ->
            val answer = parser.answer(fixture, case.query)
            assertEquals(case.query, case.found, answer.found)
            if (case.mustContain != null) assertTrue(case.query, answer.text.contains(case.mustContain))
            else assertTrue(answer.text.contains("No matching evidence", true))
        }
    }

    @Test fun matchingEmployerReturnsOneBlockAndHighlightsMatch() {
        val answer = parser.answer(fixture, "Tech Mahindra")
        assertTrue(answer.found)
        assertTrue(answer.text.contains("⟦Tech Mahindra⟧"))
        assertFalse(answer.text.contains("Acme Systems"))
        assertEquals(1, answer.evidence.size)
    }

    @Test fun focusedFieldQuestionsReturnOnlyMatchingResumeEvidence() {
        val email = parser.answer(fixture, "What is Alex's email?")
        assertTrue(email.found)
        assertTrue(email.text.contains("alex.morgan@example.com"))

        val phone = parser.answer(fixture, "What is Alex's phone number?")
        assertTrue(phone.found)
        assertTrue(phone.text.contains("415 555 0182"))

        val skills = parser.answer(fixture, "What skills does Alex have?")
        assertTrue(skills.found)
        assertTrue(skills.text.contains("Kotlin"))
        assertTrue(skills.text.contains("SQL"))

        val education = parser.answer(fixture, "What is Alex's education?")
        assertTrue(education.found)
        assertTrue(education.text.contains("BSc Computer Science"))

        val languages = parser.answer(fixture, "Which languages does Alex speak?")
        assertTrue(languages.found)
        assertTrue(languages.text.contains("Hindi"))
    }

    @Test fun previousEmployerQuestionSelectsAndHighlightsOneEarlierPipeDelimitedJob() {
        val answer = parser.answer(fixture, "What was Alex's previous employer and role?")
        assertTrue(answer.found)
        assertTrue(answer.text.contains("⟦Acme Systems⟧"))
        assertTrue(answer.text.contains("⟦Engineer⟧"))
        assertFalse(answer.text.contains("Globex"))
        assertEquals(1, answer.evidence.size)
    }

    @Test fun noNameIsNotFabricatedOrReplacedWithAnonymous() {
        val anonymousSource = "SUMMARY\nExperienced analyst supporting data teams.\nSKILLS\nExcel, SQL\nEXPERIENCE\nAnalyst | Northwind | Remote | 2020 - Present\n- Prepared SQL reports."
        val parsed = parser.parse(anonymousSource)
        assertNull(parsed.name)
        assertFalse(parsed.name == "(anonymous)")
        val answer = parser.answer(anonymousSource, "What is the analyst's name?")
        assertFalse(answer.found)
        assertTrue(answer.text.contains("No matching evidence"))
    }

    @Test fun classifiesSimpleArticleReportAndFormConservatively() {
        assertEquals(DocumentKind.ARTICLE, parser.parse("Introduction\nThis article discusses a topic. References follow.").kind)
        assertEquals(DocumentKind.REPORT, parser.parse("Quarterly report\nFindings and recommendations are listed.").kind)
        assertEquals(DocumentKind.FORM, parser.parse("Please complete this form. Signature: ______").kind)
        assertEquals(DocumentKind.UNKNOWN, parser.parse("A few uncategorized words.").kind)
    }
}
