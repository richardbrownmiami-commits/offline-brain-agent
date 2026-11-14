package com.cue.offlinebrain.data

object PhraseChainParser {
    const val MAX_STEPS = 10
    const val MAX_COMMAND_LENGTH = 500

    fun parse(raw: String): List<String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(MAX_STEPS)
        .map { it.take(MAX_COMMAND_LENGTH) }
        .toList()

    fun preview(raw: String): String {
        val steps = parse(raw)
        return if (steps.isEmpty()) "No actions yet. Add one chat command per line."
        else steps.mapIndexed { index, action -> "${index + 1}. $action" }.joinToString("\n")
    }
}
