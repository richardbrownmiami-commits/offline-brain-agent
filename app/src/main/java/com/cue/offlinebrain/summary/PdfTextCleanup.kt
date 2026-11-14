package com.cue.offlinebrain.summary

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

data class PdfSummaryReport(
    val title: String,
    val summary: List<String>,
    val keyPoints: List<String>,
    val sections: List<String>,
    val cleanedText: String
) {
    fun toChatText(): String = buildString {
        append("Title: ").append(title).append("\n\n")
        append("Summary (extractive — source sentences, not rewritten):\n")
        append(summary.joinToString(" ").ifBlank { "I couldn't find enough readable sentences to summarize." })
        append("\n\nKey points:\n")
        if (keyPoints.isEmpty()) append("• No clear key points were found.\n")
        else keyPoints.forEach { append("• ").append(it).append('\n') }
        append("\nSections:\n")
        if (sections.isEmpty()) append("• No clear section headings were found.\n")
        else sections.forEach { append("• ").append(it).append('\n') }
        append("\n--- CLEANED TEXT ---\n").append(cleanedText)
    }
}

/** Conservative cleanup for extracted PDF text; it does not paraphrase or invent text. */
object PdfTextCleanup {
    private val pageNumber = Regex("(?i)^\\s*(?:(?:page\\s*)?\\d{1,4}(?:\\s*(?:of|/)\\s*\\d{1,4})?|[-–—]\\s*\\d+\\s*[-–—])\\s*$")
    private val numberedHeading = Regex("^\\s*\\d+(?:\\.\\d+){0,3}[.)]?\\s+.{2,100}$")
    private val bullet = Regex("^\\s*(?:[•●▪◦‣*-]|\\d+[.)])\\s+.+$")

    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = Normalizer.normalize(raw.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
            .replace('\u00A0', ' ')
        val pages = normalized.split('\u000C')
        val pageEdges = pages.map { page ->
            page.lines().map(::normalizeLine).filter(String::isNotBlank).let { lines -> listOfNotNull(lines.firstOrNull(), lines.lastOrNull()) }
        }.flatten().groupingBy { it.lowercase(Locale.ROOT) }.eachCount().filterValues { it >= 2 }.keys
        val repeated = normalized.lines().map(::normalizeLine).filter(String::isNotBlank)
            .groupingBy { normalizeFingerprint(it) }.eachCount().filterValues { it >= 3 }.keys

        val retained = normalized.lines().map(::normalizeLine).filter { line ->
            line.isNotBlank() && !isNumericNoise(line) && !pageNumber.matches(line) &&
                line.lowercase(Locale.ROOT) !in pageEdges && normalizeFingerprint(line) !in repeated
        }
        val joined = StringBuilder()
        var previousWasHeadingOrBullet = false
        var previousWasBullet = false
        for (line in retained) {
            val current = line.trim()
            if (current.isBlank()) continue
            val heading = isHeading(current)
            val isBullet = !heading && bullet.matches(current)
            val structured = heading || isBullet
            if (joined.isNotEmpty()) {
                val last = joined.lastOrNull() ?: '\n'
                if (last == '\n') {
                    // Keep the separation introduced by headings and bullet items.
                } else if (!structured && (!previousWasHeadingOrBullet || previousWasBullet)) {
                    val before = joined.toString().substringAfterLast('\n').trimEnd()
                    if (previousWasBullet) {
                        joined.append(' ').append(current)
                        previousWasHeadingOrBullet = false
                        continue
                    } else if (before.endsWith('-') && current.firstOrNull()?.isLowerCase() == true) {
                        joined.setLength(joined.length - 1)
                        joined.append(current)
                    } else {
                        joined.append(' ').append(current)
                    }
                    previousWasHeadingOrBullet = false
                    continue
                } else joined.append('\n')
            }
            joined.append(current)
            previousWasHeadingOrBullet = structured
            previousWasBullet = isBullet
        }
        return joined.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun report(raw: String, suggestedTitle: String = "Document"): PdfSummaryReport {
        val clean = clean(raw).take(MAX_CLEAN_TEXT_CHARS)
        val sections = clean.lines().filter(::isHeading)
            .map { it.trim().take(100) }
            .filter(String::isNotBlank).distinct().take(MAX_SECTIONS)
        val resume = ResumeDocumentParser().parse(clean)
        val detectedName = resume.name.takeIf { resume.kind == DocumentKind.RESUME }
        val heading = clean.lines().firstOrNull(::isHeading)?.trim()?.take(120)
        val metadataTitle = suggestedTitle.trim().takeIf {
            it.isNotBlank() && !it.equals("Document", true) && !it.equals("(anonymous)", true)
        }
        val title = detectedName?.takeIf(String::isNotBlank)?.take(120)
            ?: metadataTitle?.take(120)
            ?: heading
            ?: clean.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
            ?: "Document"
        val sentences = extractSentences(clean)
        if (sentences.isEmpty()) return PdfSummaryReport(title, emptyList(), emptyList(), sections, clean)
        val scores = rank(sentences)
        val summaryCount = minOf(MAX_SUMMARY_SENTENCES, sentences.size).coerceAtLeast(1)
        val keyPointCount = minOf(MAX_KEY_POINTS, sentences.size)
        val summaryIndices = scores.indices.sortedByDescending { scores[it] }.take(summaryCount)
        val keyPointIndices = scores.indices.filterNot { it in summaryIndices }
            .sortedByDescending { scores[it] }.take(keyPointCount)
        val summary = summaryIndices.sorted().map { sentences[it] }
        val keyPoints = keyPointIndices.sorted().map { sentences[it] }
        val sectionLines = if (sections.isNotEmpty()) sections else paragraphs(clean).take(MAX_SECTIONS).map { it.take(100) }
        return PdfSummaryReport(title, summary, keyPoints, sectionLines, clean)
    }

    private fun extractSentences(text: String): List<String> = text.lines()
        .filterNot { isHeading(it) || bullet.matches(it) }
        .joinToString(" ") { it.trim() }
        .split(Regex("(?<=[.!?])\\s+(?=[\\p{Lu}0-9])|\\s*[•●▪◦‣]\\s*"))
        .map { it.trim().trimStart('-', '*').trim() }
        .filter { sentence ->
            if (sentence.length < MIN_SENTENCE_CHARS) return@filter false
            val lettersOrDigits = sentence.count { it.isLetterOrDigit() }
            val digits = sentence.count(Char::isDigit)
            lettersOrDigits.toDouble() / sentence.length >= 0.48 && digits.toDouble() / sentence.length < 0.55
        }
        .distinct()
        .take(MAX_SENTENCES)

    private fun rank(sentences: List<String>): List<Double> {
        val tokens = sentences.map { terms(it).toSet() }
        if (sentences.size == 1) return listOf(1.0)
        val scores = MutableList(sentences.size) { 1.0 / sentences.size }
        repeat(12) {
            val next = MutableList(sentences.size) { 0.15 / sentences.size }
            for (i in sentences.indices) {
                var weight = 0.0
                for (j in sentences.indices) if (i != j) weight += overlap(tokens[i], tokens[j])
                if (weight <= 0.0) {
                    val share = 0.85 * scores[i] / sentences.size
                    for (j in sentences.indices) next[j] += share
                } else {
                    for (j in sentences.indices) if (i != j) {
                        val edge = overlap(tokens[i], tokens[j])
                        if (edge > 0.0) next[j] += 0.85 * scores[i] * edge / weight
                    }
                }
            }
            for (i in sentences.indices) {
                val position = if (i == 0) 0.08 else 0.0
                next[i] += position
                scores[i] = next[i]
            }
        }
        val max = scores.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val termFrequency = tokens.flatten().groupingBy { it }.eachCount()
        val maxFrequency = termFrequency.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        return sentences.indices.map { index ->
            val pagerank = scores[index] / max
            val keyword = tokens[index].sumOf { ln(1.0 + (termFrequency[it] ?: 0)) } / sqrt(1.0 + tokens[index].size)
            val keywordNorm = keyword / (1.0 + ln(maxFrequency.toDouble() + 1.0))
            val firstSentenceBoost = if (index == 0) 0.08 else 0.0
            pagerank * 0.72 + keywordNorm * 0.28 + firstSentenceBoost
        }
    }

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }.toDouble()
        return shared / (ln(1.0 + a.size) + ln(1.0 + b.size))
    }

    private fun terms(value: String): List<String> = value.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 && it !in STOP_WORDS }

    private fun isHeading(line: String): Boolean {
        val value = line.trim()
        if (value.length !in 3..100) return false
        if (value.endsWith('-')) return false
        if (numberedHeading.matches(value)) return true
        if (bullet.matches(value)) return false
        if (value.any(Char::isLetter) && value == value.uppercase(Locale.ROOT) && value.count(Char::isLetter) >= 3) return true
        return !value.endsWith('.') && !value.endsWith('?') && !value.endsWith('!') &&
            value.split(Regex("\\s+")).size <= 3 && value.length <= 40
    }

    private fun normalizeLine(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun isNumericNoise(value: String): Boolean {
        val alphanumeric = value.count(Char::isLetterOrDigit)
        val digits = value.count(Char::isDigit)
        return alphanumeric >= 24 && digits >= 20 && digits.toDouble() / alphanumeric >= 0.85
    }

    private fun normalizeFingerprint(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\d+"), "#").replace(Regex("[^\\p{L}#]+"), "").take(100)

    private fun paragraphs(text: String): List<String> = text.split(Regex("\\n{2,}"))
        .map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 30 }

    private const val MIN_SENTENCE_CHARS = 35
    private const val MAX_CLEAN_TEXT_CHARS = 200_000
    private const val MAX_SENTENCES = 2_000
    private const val MAX_SUMMARY_SENTENCES = 5
    private const val MAX_KEY_POINTS = 8
    private const val MAX_SECTIONS = 12
    private val STOP_WORDS = setOf("the", "and", "for", "that", "this", "with", "from", "was", "were", "are", "but", "you", "your", "have", "has", "had", "its", "into", "than", "then", "they", "them", "their", "there", "here", "who", "what", "when", "where", "which", "will", "would", "could", "should")
}
