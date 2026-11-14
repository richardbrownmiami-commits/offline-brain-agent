package com.cue.offlinebrain.engine

import java.text.Normalizer
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A deliberately narrow local router for conversational small-talk. */
class SmallTalkRouter(private val clock: Clock = Clock.systemDefaultZone()) {

    /** Returns a local reply for recognized small-talk, or null when another router should decide. */
    fun reply(input: String): String? {
        val text = normalize(input)
        if (text.isEmpty() || isActionable(text)) return null

        fixedReplies[text]?.let { return it }
        if (text.matches(Regex("(?:hi|hello|hey|good morning|good afternoon|good evening)\\s+(?:there|friend)"))) {
            return "Hello! How can I help?"
        }
        if (text.matches(Regex("(?:tell me )?(?:a )?(?:joke|funny joke)"))) {
            return "Why did the computer get cold? It left its Windows open."
        }
        if (text.matches(Regex("(?:what day is it|what is the day|which day is it)(?: today)?"))) {
            return "Today is ${DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH).format(clock.instant().atZone(clock.zone))}."
        }
        if (text.matches(Regex("(?:what (?:is the )?)?time(?: is it)?(?: now| right now)?"))) {
            val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
                .format(clock.instant().atZone(clock.zone))
                .replace('\u202f', ' ').replace('\u00a0', ' ')
            return "The time is $time."
        }
        if (text.matches(Regex("(?:what is (?:the )?)?(?:today s )?date(?: today)?"))) {
            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.ENGLISH)
                .format(clock.instant().atZone(clock.zone))
            return "Today is $date."
        }
        return null
    }

    private fun isActionable(text: String): Boolean =
        text.matches(Regex("(?:please )?(?:open|launch|start|close|tap|click|press|turn on|turn off|enable|disable|search|find|set|send|call|play|type|scroll|go to|navigate to|show me|run|create|delete)\\b.*")) ||
            (text.contains(Regex("\\b(?:accessibility|settings|screen|service)\\b")) &&
                text.matches(Regex(".*\\b(?:open|launch|turn on|turn off|enable|disable|go to|tap|click)\\b.*")))

    private fun normalize(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private companion object {
        val fixedReplies = mapOf(
            "hi" to "Hello! How can I help?", "hello" to "Hello! How can I help?",
            "hey" to "Hey! How can I help?", "good morning" to "Good morning! How can I help?",
            "good afternoon" to "Good afternoon! How can I help?", "good evening" to "Good evening! How can I help?",
            "how are you" to "I'm doing well, thanks for asking! How can I help you?",
            "how are you doing" to "I'm doing well, thanks for asking! How can I help you?",
            "how is it going" to "Going well, thanks! How can I help?",
            "thanks" to "You're welcome!", "thank you" to "You're welcome!",
            "thanks a lot" to "You're very welcome!", "thank you very much" to "You're very welcome!",
            "who are you" to "I'm your offline assistant. I can help with supported tasks on this device.",
            "what are you" to "I'm your offline assistant, here to help with supported tasks.",
            "what is your name" to "I'm your offline assistant.",
            "who made you" to "I'm an offline assistant built to help with supported tasks.",
            "what can you do" to "I can help with supported on-device tasks and answer some simple questions.",
            "what do you do" to "I help with supported tasks on this device.",
            "how can you help me" to "I can help with supported on-device tasks. Tell me what you need.",
            "can you help me" to "I'll do my best. What would you like help with?"
        )
    }
}
