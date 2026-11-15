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

    @Test fun positiveCorpusContainsAtLeastSixtyDistinctNewPhrasesAndParses() {
        val examples = NotificationQueryRegressionCases.positivePhrases
        assertEquals("Corpus must not gain duplicates", examples.size, examples.distinct().size)
        assertTrue("At least sixty new distinct phrases are retained", examples.size >= 112)
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
            val expectsUnread = example.unreadOnly || Regex("\\b(latest|newest)\\b", RegexOption.IGNORE_CASE).containsMatchIn(example.text)
            assertEquals("Unread modifier for ${example.text}", expectsUnread, query.unreadOnly)
            assertEquals("Today modifier for ${example.text}", example.todayOnly, query.todayOnly)
        }
    }

    @Test fun latestAndMarkUnreadIntents() {
        for (phrase in listOf("latest", "latest unseen")) {
            val q = parsed(phrase + " messages").query
            assertEquals(NotificationQueryParser.Category.MESSAGES_SMS, q.category)
            assertTrue(q.unreadOnly)
        }
        val all = parsed("mark all unread").query
        assertEquals(NotificationQueryParser.Action.MARK_UNREAD, all.action)
        assertEquals(null, all.category)
        assertTrue(all.unreadOnly)
        assertEquals(NotificationQueryParser.Category.CALLS, parsed("mark calls unread").query.category)
        assertEquals(NotificationQueryParser.Category.MESSAGES_SMS, parsed("mark text unread").query.category)
    }

    @Test fun destructiveTimeFiltersAndReplyAreBoundedAndStructured() {
        val examples = listOf(
            Triple("delete calls today", NotificationQueryParser.Action.DELETE, NotificationQueryParser.Category.CALLS),
            Triple("clear text yesterday", NotificationQueryParser.Action.CLEAR, NotificationQueryParser.Category.MESSAGES_SMS),
            Triple("delete email last 5 minutes", NotificationQueryParser.Action.DELETE, NotificationQueryParser.Category.EMAIL),
            Triple("clear chat last 2 hours", NotificationQueryParser.Action.CLEAR, NotificationQueryParser.Category.CHAT),
            Triple("delete calls last 3 days", NotificationQueryParser.Action.DELETE, NotificationQueryParser.Category.CALLS),
            Triple("clear notifications older than 30 days", NotificationQueryParser.Action.CLEAR, null)
        )
        examples.forEach { (phrase, action, category) ->
            val q = parsed(phrase).query
            assertEquals(action, q.action)
            assertEquals(category, q.category)
        }
        assertTrue(parsed("delete calls today").query.todayOnly)
        assertTrue(parsed("clear text yesterday").query.yesterdayOnly)
        assertEquals(5, parsed("delete email last 5 minutes").query.lastMinutes)
        assertEquals(2, parsed("clear chat last 2 hours").query.lastHours)
        assertEquals(3, parsed("delete calls last 3 days").query.lastDays)
        assertEquals(30, parsed("clear notifications older than 30 days").query.olderThanDays)
        val reply = parsed("reply to Alex Smith: I will call you").query
        assertEquals(NotificationQueryParser.Action.REPLY, reply.action)
        assertEquals("Alex Smith", reply.contact)
        assertEquals("I will call you", reply.replyText)
        assertTrue(NotificationQueryParser.parse("reply to Alex: ${"x".repeat(501)}") is NotificationQueryParser.Outcome.Rejected)
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
        category("sho calls", NotificationQueryParser.Category.CALLS)
        category("shw clls", NotificationQueryParser.Category.CALLS)
        assertTrue(NotificationQueryParser.parse("shoo calls").let { it is NotificationQueryParser.Outcome.Parsed && it.query.action == NotificationQueryParser.Action.SHOW })
        assertTrue(NotificationQueryParser.parse("shoooo calls").let { it is NotificationQueryParser.Outcome.Parsed && it.query.action == NotificationQueryParser.Action.SHOW })
        assertTrue(NotificationQueryParser.parse("shw cals") is NotificationQueryParser.Outcome.Parsed)
        assertTrue(NotificationQueryParser.parse("mesage notification") is NotificationQueryParser.Outcome.Parsed)
        assertTrue(NotificationQueryParser.parse("messa notification") is NotificationQueryParser.Outcome.Clarify)
        assertTrue(NotificationQueryParser.parse("show mxxxages notification") is NotificationQueryParser.Outcome.Parsed)
    }
}
