package com.cue.offlinebrain.summary

import kotlin.math.ln

/** Extracts sentences from source text; it never invents or paraphrases content. */
class ExtractiveSummarizer {
    fun summarize(text: String, maxSentences: Int = 5, focus: String? = null): String {
        val sentences = text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9])|\\n+") )
            .map { it.trim() }.filter { it.length >= 35 }
        if (sentences.isEmpty()) return text.trim().take(1400).ifBlank { "I couldn't find enough readable text to summarize." }
        if (sentences.size <= maxSentences) return sentences.joinToString(" ")
        val words = sentences.map { terms(it) }
        val freq = words.flatten().groupingBy { it }.eachCount()
        val focusTerms = focus?.let(::terms).orEmpty()
        val scores = sentences.indices.map { i ->
            val terms = words[i]
            val tf = terms.sumOf { ln(1.0 + (freq[it] ?: 0)) } / (1.0 + terms.size.toDouble()).let { kotlin.math.sqrt(it) }
            val queryBoost = if (focusTerms.isEmpty()) 0.0 else terms.count { it in focusTerms }.toDouble() / focusTerms.size * 2.0
            val positionBoost = if (i == 0) 0.35 else if (i == 1) 0.12 else 0.0
            tf + queryBoost + positionBoost
        }
        return scores.indices.sortedByDescending { scores[it] }.take(maxSentences).sorted().joinToString(" ") { sentences[it] }
    }

    fun keyPoints(text: String, count: Int = 5): List<String> {
        val summary = summarize(text, count * 2)
        return summary.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.take(count).map { "• $it" }
    }

    private fun terms(value: String): List<String> = value.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 && it !in setOf("the", "and", "for", "that", "this", "with", "from", "was", "were", "are", "but", "you", "your") }
}
