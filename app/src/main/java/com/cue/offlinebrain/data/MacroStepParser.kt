package com.cue.offlinebrain.data

object MacroStepParser {
    fun parse(raw: String): List<String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(50)
        .map { it.take(500) }
        .toList()
}
