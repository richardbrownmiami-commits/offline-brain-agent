package com.cue.offlinebrain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationQueryParserTest {
    private fun parsed(text: String) = NotificationQueryParser.parse(text) as NotificationQueryParser.Outcome.Parsed
    private fun category(text: String, expected: NotificationQueryParser.Category) = assertEquals(expected, parsed(text).query.category)

    @Test fun requiredRegressionExamples() {
        category("what in music notification", NotificationQueryParser.Category.MUSIC_AND_MEDIA)
        category("tell me about what's in text sms notification", NotificationQueryParser.Category.MESSAGES_SMS)
        category("text notification", NotificationQueryParser.Category.MESSAGES_SMS)
        category("what is in sms notification", NotificationQueryParser.Category.MESSAGES_SMS)
        category("read system notification", NotificationQueryParser.Category.SYSTEM)
        category("sho calls", NotificationQueryParser.Category.CALLS)
        assertTrue(parsed("any new notification").query.unreadOnly)
        assertTrue(parsed("what notifications did I get").query.category == null)
    }

    @Test fun allFiftyTwoPositivePhrasesAreRetainedAndParse() {
        val examples = NotificationQueryRegressionCases.positivePhrases
        assertEquals("Keep every established phrase exactly once", 52, examples.distinct().size)
        examples.forEach { assertTrue("Should parse: $it", NotificationQueryParser.parse(it) is NotificationQueryParser.Outcome.Parsed) }
    }

    @Test fun fiftyNaturalSentencesScoreToExpectedIntent() {
        val examples = NotificationQueryRegressionCases.naturalSentences
        assertEquals("Keep the 50-sentence natural-language set complete", 50, examples.size)
        assertEquals("Natural-language examples should be distinct", 50, examples.map { it.text }.distinct().size)
        examples.forEach { example ->
            val result = NotificationQueryParser.parse(example.text)
            assertTrue("Should parse: ${example.text}; got $result", result is NotificationQueryParser.Outcome.Parsed)
            val query = (result as NotificationQueryParser.Outcome.Parsed).query
            assertEquals("Category for ${example.text}", example.category, query.category)
            assertEquals("Action for ${example.text}", example.action, query.action)
            assertEquals("Unread modifier for ${example.text}", example.unreadOnly, query.unreadOnly)
            assertEquals("Today modifier for ${example.text}", example.todayOnly, query.todayOnly)
        }
    }

    @Test fun supportsModifiersAndBoundedMetadata() {
        val q = parsed("show unread messages today from com.example.mail").query
        assertEquals(NotificationQueryParser.Category.MESSAGES_SMS, q.category)
        assertTrue(q.unreadOnly)
        assertTrue(q.todayOnly)
        assertEquals("com.example.mail", q.fromApp)
        assertEquals("Alex Smith", parsed("what did Alex Smith send").query.contact)
        assertEquals(null, parsed("Notification").query.category)
    }

    @Test fun ambiguousAndInvalidInputFailsClosed() {
        assertTrue(NotificationQueryParser.parse("show calls music notification") is NotificationQueryParser.Outcome.Clarify)
        assertTrue(NotificationQueryParser.parse("show blargh notification") is NotificationQueryParser.Outcome.Parsed)
        assertTrue(NotificationQueryParser.parse("show blargh") is NotificationQueryParser.Outcome.Rejected)
        assertTrue(NotificationQueryParser.parse("") is NotificationQueryParser.Outcome.Rejected)
        assertTrue(NotificationQueryParser.parse("notification") is NotificationQueryParser.Outcome.Parsed)
        assertTrue(NotificationQueryParser.parse("show notifications from") is NotificationQueryParser.Outcome.Rejected)
        assertTrue(NotificationQueryParser.parse("what did Alex send").let { it is NotificationQueryParser.Outcome.Parsed && it.query.contact == "Alex" })
        assertTrue(NotificationQueryParser.parse("what did Alex! send") is NotificationQueryParser.Outcome.Rejected)
    }

    @Test fun finiteVocabularyTypoCorrectionAndDistanceBoundary() {
        category("sho calls", NotificationQueryParser.Category.CALLS) // distance 1: show
        category("shw clls", NotificationQueryParser.Category.CALLS) // distance 2 for each command/category token
        assertTrue(NotificationQueryParser.parse("shoo calls").let { it is NotificationQueryParser.Outcome.Parsed && it.query.action == NotificationQueryParser.Action.SHOW })
        assertTrue(NotificationQueryParser.parse("shoooo calls").let { it is NotificationQueryParser.Outcome.Parsed && it.query.action == NotificationQueryParser.Action.SHOW }) // distance 3 is ignored as an extra word
        assertTrue(NotificationQueryParser.parse("shw cals") is NotificationQueryParser.Outcome.Parsed)
        assertTrue(NotificationQueryParser.parse("mesage notification") is NotificationQueryParser.Outcome.Parsed)
        assertTrue(NotificationQueryParser.parse("messa notification") is NotificationQueryParser.Outcome.Clarify)
        assertTrue(NotificationQueryParser.parse("show mxxxages notification") is NotificationQueryParser.Outcome.Parsed)
    }
}
