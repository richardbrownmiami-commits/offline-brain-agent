package com.cue.offlinebrain.engine

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt

internal data class IntentRule(val name: String, val examples: List<String>, val keywords: Set<String>, val regex: Regex? = null)
data class AlarmSlot(val triggerAtMillis: Long, val label: String, val cron: String? = null)
data class IntentMatch(val name: String, val confidence: Double, val city: String? = null, val alarm: AlarmSlot? = null, val query: String? = null)

/** Entirely local, deterministic routing; it does not call a language model or remote AI. */
class IntentEngine {
    private val rules = listOf(
        IntentRule("weather", listOf("weather today", "forecast", "temperature in city", "will it rain"), setOf("weather", "forecast", "temperature", "rain", "raining", "climate"), Regex("\\b(weather|forecast|temperature|rain|raining)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("news", listOf("latest news", "headlines", "world news"), setOf("news", "headlines", "latest", "breaking"), Regex("\\b(news|headlines)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("alarm", listOf("set alarm for 7 am", "remind me tomorrow", "alarm at 6", "wake me up at 6 am"), setOf("alarm", "remind", "reminder", "wake", "cron", "schedule"), Regex("\\b(alarm|remind|reminder|wake me up|schedule)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("currency", listOf("convert usd to inr", "exchange rate eur"), setOf("convert", "currency", "exchange", "rate", "usd", "inr", "eur"), Regex("\\b(convert|currency|exchange rate)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("wiki", listOf("who is Ada Lovelace", "what is photosynthesis", "define gravity"), setOf("who", "what", "define", "explain", "tell", "about", "meaning"), Regex("\\b(who is|what is|who was|what are|define|explain)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("summary", listOf("summarise this pdf", "summarize this page", "key points"), setOf("summarize", "summarise", "summary", "pdf", "document", "key", "points"), Regex("\\b(summar(y|ize|ise)|pdf|key points)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("open_app", listOf("open settings app", "launch chrome"), setOf("open", "launch", "start", "app"), Regex("\\b(open|launch|start)\\s+.+", RegexOption.IGNORE_CASE)),
        IntentRule("tap", listOf("tap next", "click submit", "scroll down", "type mom"), setOf("tap", "click", "scroll", "type", "back", "home"), Regex("\\b(tap|click|scroll|type|go back|press home)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("note", listOf("remember buy milk", "add note", "todo finish report"), setOf("note", "remember", "todo", "task", "checklist"), Regex("\\b(note|remember|todo|to-do|task)\\b", RegexOption.IGNORE_CASE)),
        IntentRule("greeting", listOf("hello", "hi there", "good morning"), setOf("hello", "hi", "hey", "morning", "evening"), Regex("^(hi|hello|hey|good morning|good evening)[.! ]*$", RegexOption.IGNORE_CASE)),
        IntentRule("help", listOf("what can you do", "help me"), setOf("help", "capabilities", "features"), Regex("\\b(help|what can you do|capabilities)\\b", RegexOption.IGNORE_CASE))
    )
    private val stopWords = setOf("a", "an", "the", "is", "are", "was", "were", "in", "at", "to", "for", "me", "my", "please", "can", "you", "it", "of", "on", "this", "that")

    fun tokenize(text: String): List<String> = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() && it !in stopWords }

    fun classify(text: String, customPhrase: String = "", defaultCity: String = "New Delhi", nowMillis: Long = System.currentTimeMillis()): IntentMatch {
        val queryTokens = tokenize(text)
        if (queryTokens.isEmpty()) return IntentMatch("unknown", 0.0)
        val idf = queryTokens.associateWith { token ->
            val documents = rules.count { rule -> token in tokenize(rule.examples.joinToString(" ") + " " + rule.keywords.joinToString(" ")) }
            kotlin.math.ln(1.0 + rules.size.toDouble() / (1.0 + documents))
        }
        val ranked = rules.map { rule ->
            val doc = tokenize(rule.examples.joinToString(" ") + " " + rule.keywords.joinToString(" "))
            val docCounts = doc.groupingBy { it }.eachCount()
            val queryCounts = queryTokens.groupingBy { it }.eachCount()
            var dot = 0.0; var qNorm = 0.0; var dNorm = 0.0
            (queryCounts.keys + docCounts.keys).forEach { token ->
                val q = (queryCounts[token] ?: 0) * (idf[token] ?: 1.0)
                val d = (docCounts[token] ?: 0) * (idf[token] ?: 1.0)
                dot += q * d; qNorm += q * q; dNorm += d * d
            }
            val cosine = if (qNorm == 0.0 || dNorm == 0.0) 0.0 else dot / (sqrt(qNorm) * sqrt(dNorm))
            val fuzzy = queryTokens.maxOfOrNull { q -> rule.keywords.maxOfOrNull { k -> fuzzyScore(q, k) } ?: 0.0 } ?: 0.0
            val regexBonus = if (rule.regex?.containsMatchIn(text) == true) 0.48 else 0.0
            val phraseBonus = if (rule.examples.any { text.contains(it, ignoreCase = true) }) 0.3 else 0.0
            rule.name to (cosine * 0.62 + fuzzy * 0.18 + regexBonus + phraseBonus)
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull()
        val custom = customPhrase.trim()
        if (custom.isNotEmpty() && text.contains(custom, ignoreCase = true)) return IntentMatch("custom", 1.0, query = text)
        val name = if (best != null && best.second >= 0.28) best.first else "unknown"
        val city = Regex("\\b(?:in|for|near)\\s+([\\p{L}][\\p{L} .'-]{1,35})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '?')
            ?: defaultCity
        val alarm = if (name == "alarm") parseAlarm(text, nowMillis) else null
        val query = when (name) {
            "wiki" -> text.replace(Regex("(?i)\\b(who is|who was|what is|what are|define|explain|tell me about)\\b"), "").trim().trimEnd('?', '.')
            else -> null
        }
        return IntentMatch(name, best?.second ?: 0.0, city, alarm, query)
    }

    private fun fuzzyScore(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 4 || b.length < 4) return 0.0
        if (a.startsWith(b) || b.startsWith(a)) return 0.7
        if (kotlin.math.abs(a.length - b.length) > 1) return 0.0
        return if (editDistanceAtMostOne(a, b)) 0.55 else 0.0
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                edits++; if (edits > 1) return false
                if (a.length > b.length) i++ else if (b.length > a.length) j++ else { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    private fun parseAlarm(text: String, nowMillis: Long): AlarmSlot? {
        val interval = Regex("(?i)\\bin\\s+(\\d{1,3})\\s+(minute|minutes|min|hour|hours)\\b").find(text)
        if (interval != null) {
            val amount = interval.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
            val unit = interval.groupValues.getOrNull(2).orEmpty()
            val factor = if (unit.startsWith("h", true)) 3_600_000L else 60_000L
            return AlarmSlot(nowMillis + amount * factor, text.take(70))
        }
        val tm = Regex("(?i)(?:\\bat\\s+|\\bfor\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(text) ?: return null
        val rawHour = tm.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = tm.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (rawHour !in 1..12 || minute !in 0..59) return null
        val hour = (rawHour % 12) + if (tm.groupValues.getOrNull(3).orEmpty().equals("pm", true)) 12 else 0
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            when {
                text.contains("tomorrow", true) -> add(Calendar.DAY_OF_YEAR, 1)
                text.contains("today", true) -> Unit
                else -> if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        Regex("(?i)every\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)").find(text)?.let { m ->
            val weekday = m.groupValues.getOrNull(1).orEmpty().lowercase()
            val dow = mapOf("sunday" to 0, "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6)[weekday]
            val dayCron = if (dow == 0) 0 else dow
            return AlarmSlot(cal.timeInMillis, text.take(70), "$minute $hour * * $dayCron")
        }
        return AlarmSlot(cal.timeInMillis, text.take(70))
    }
}
