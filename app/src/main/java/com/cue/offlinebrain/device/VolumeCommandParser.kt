package com.cue.offlinebrain.device

/** Typed local interpretation of a narrowly-scoped media-volume command. */
sealed class VolumeCommand {
    object IncreaseOneStep : VolumeCommand()
    object DecreaseOneStep : VolumeCommand()
    data class SetPercent(val percent: Int) : VolumeCommand()
    object Mute : VolumeCommand()
    object Read : VolumeCommand()
    object NotCommand : VolumeCommand()
}

/**
 * Deterministic parser; fuzzy matching is limited to command tokens, never arbitrary text.
 * In particular, "rise volume N" means set media target percentage N (0..100), not +N steps.
 */
object VolumeCommandParser {
    fun parse(input: String): VolumeCommand {
        val tokens = tokenize(input) ?: return VolumeCommand.NotCommand
        if (tokens.isEmpty()) return VolumeCommand.NotCommand

        val normalized = tokens.map { token ->
            canonicalToken(token) ?: return VolumeCommand.NotCommand
        }
        val words = normalized.filterNot { it == "to" || it == "the" }
        if (words.isEmpty()) return VolumeCommand.NotCommand

        // Numeric forms are parsed separately and must contain exactly one unsigned integer.
        val numbers = words.filter { it.all(Char::isDigit) }
        if (numbers.isNotEmpty()) {
            if (numbers.size != 1) return VolumeCommand.NotCommand
            val number = numbers.single().toIntOrNull() ?: return VolumeCommand.NotCommand
            if (number !in 0..100) return VolumeCommand.NotCommand
            val numberIndex = words.indexOf(numbers.single())
            val prefix = words.take(numberIndex)
            val suffix = words.drop(numberIndex + 1)
            val acceptedPrefixes = setOf(
                listOf("rise", "volume"),
                listOf("set", "volume"),
                listOf("set", "volume", "to"),
                listOf("volume", "set")
            )
            if (prefix !in acceptedPrefixes || suffix !in setOf(emptyList(), listOf("percent"))) {
                return VolumeCommand.NotCommand
            }
            return VolumeCommand.SetPercent(number)
        }

        return when (words) {
            listOf("volume", "up"), listOf("increase", "volume"), listOf("raise", "volume"),
            listOf("rise", "volume") -> VolumeCommand.IncreaseOneStep
            listOf("volume", "down"), listOf("decrease", "volume"), listOf("lower", "volume") ->
                VolumeCommand.DecreaseOneStep
            listOf("mute"), listOf("volume", "mute") -> VolumeCommand.Mute
            listOf("read", "volume"), listOf("volume") -> VolumeCommand.Read
            else -> VolumeCommand.NotCommand
        }
    }

    private fun tokenize(input: String): List<String>? {
        val trimmed = input.trim().replace("%", " percent")
        if (trimmed.isEmpty()) return emptyList()
        // Only allow punctuation as token boundaries or sentence wrappers; reject symbols,
        // apostrophes and embedded punctuation that could hide unrelated content.
        if (trimmed.any { !(it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?;:") }) return null
        return trimmed.lowercase().split(Regex("[\\s.,!?;:]+")).filter { it.isNotEmpty() }
    }

    private val commandVocabulary = setOf(
        "rise", "volume", "set", "percent", "up", "increase", "raise", "down", "decrease",
        "lower", "mute", "read", "to", "the"
    )

    private fun canonicalToken(token: String): String? {
        if (token.all(Char::isDigit)) return token
        if (token in commandVocabulary) return token
        // A one-edit correction is allowed only when exactly one vocabulary token matches.
        val matches = commandVocabulary.filter { levenshteinAtMostOne(token, it) }
        return matches.singleOrNull()
    }

    private fun levenshteinAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            var mismatches = 0
            for (i in a.indices) if (a[i] != b[i] && ++mismatches > 1) return false
            return true
        }
        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        var i = 0
        var j = 0
        var edits = 0
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) { i++; j++ } else {
                if (++edits > 1) return false
                j++
            }
        }
        return true
    }
}
