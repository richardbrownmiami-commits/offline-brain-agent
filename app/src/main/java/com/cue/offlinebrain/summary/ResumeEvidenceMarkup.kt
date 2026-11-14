package com.cue.offlinebrain.summary

data class EvidenceTextSegment(val text: String, val highlighted: Boolean)

/** Parses the resume parser's internal ⟦term⟧ marker without changing source words. */
object ResumeEvidenceMarkup {
    fun segments(value: String): List<EvidenceTextSegment> {
        if (!value.contains('⟦') || !value.contains('⟧')) return listOf(EvidenceTextSegment(value, false))
        val result = mutableListOf<EvidenceTextSegment>()
        var cursor = 0
        while (cursor < value.length) {
            val open = value.indexOf('⟦', cursor)
            if (open < 0) {
                result += EvidenceTextSegment(value.substring(cursor), false)
                break
            }
            if (open > cursor) result += EvidenceTextSegment(value.substring(cursor, open), false)
            val close = value.indexOf('⟧', open + 1)
            if (close < 0) {
                result += EvidenceTextSegment(value.substring(open), false)
                break
            }
            val term = value.substring(open + 1, close)
            if (term.isEmpty()) result += EvidenceTextSegment("⟦⟧", false)
            else result += EvidenceTextSegment(term, true)
            cursor = close + 1
        }
        return result
    }

    fun plain(value: String): String = segments(value).joinToString(separator = "") { it.text }
}
