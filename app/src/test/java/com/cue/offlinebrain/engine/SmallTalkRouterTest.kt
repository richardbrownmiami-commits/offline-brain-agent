package com.cue.offlinebrain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SmallTalkRouterTest {
    private val clock = Clock.fixed(Instant.parse("2025-01-02T15:04:00Z"), ZoneId.of("UTC"))
    private val router = SmallTalkRouter(clock)

    @Test fun fortyDistinctSmallTalkPhrasesAreHandledWithoutAccessibilityReply() {
        val phrases = listOf(
            "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
            "how are you", "how are you doing", "how is it going", "thanks", "thank you",
            "thanks a lot", "thank you very much", "who are you", "what are you",
            "what is your name", "who made you", "what can you do", "what do you do",
            "how can you help me", "can you help me", "tell me a joke", "tell me a funny joke",
            "joke", "funny joke", "what time is it", "what is the time", "time",
            "time now", "time right now", "what is today's date", "what is the date",
            "date", "date today", "what day is it", "what day is it today",
            "what is the day", "which day is it", "hi there", "hello friend"
        )
        assertEquals(40, phrases.size)
        assertEquals(40, phrases.toSet().size)
        phrases.forEach { phrase ->
            val reply = router.reply(phrase)
            assertNotNull("Expected small-talk reply for: $phrase", reply)
            assertFalse("Small-talk reply must never be Accessibility setup: $phrase -> $reply",
                reply!!.contains("accessibility", ignoreCase = true))
        }
    }

    @Test fun actionableDeviceRequestsRemainUnclaimed() {
        assertNull(router.reply("open Accessibility settings"))
        assertNull(router.reply("turn on accessibility service"))
        assertNull(router.reply("tap Search"))
    }

    @Test fun unfamiliarFactualQuestionRemainsUnclaimed() {
        assertNull(router.reply("Who won the 1998 World Cup?"))
    }

    @Test fun temporalRepliesUseInjectedClock() {
        assertEquals("The time is 3:04 PM.", router.reply("what time is it"))
        assertEquals("Today is January 2, 2025.", router.reply("what is the date"))
        assertEquals("Today is Thursday.", router.reply("what day is it"))
    }

    @Test fun normalizationHandlesPunctuationAndCaseWithoutBroadMatching() {
        assertEquals(router.reply("how are you"), router.reply("HOW ARE YOU?!"))
        assertNull(router.reply("how are you doing with the weather in Tokyo"))
    }
}
