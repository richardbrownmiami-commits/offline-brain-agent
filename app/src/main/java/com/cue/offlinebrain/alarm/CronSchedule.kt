package com.cue.offlinebrain.alarm

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Small, dependency-free five-field cron parser: minute hour day-of-month month day-of-week. */
data class CronSchedule private constructor(
    private val minutes: Set<Int>, private val hours: Set<Int>, private val days: Set<Int>,
    private val months: Set<Int>, private val weekdays: Set<Int>
) {
    fun matches(value: LocalDateTime): Boolean {
        val cronDow = value.dayOfWeek.value % 7 // Sunday=0
        return value.minute in minutes && value.hour in hours && value.dayOfMonth in days &&
            value.monthValue in months && (cronDow in weekdays || (cronDow == 0 && 7 in weekdays))
    }

    fun nextAfter(now: LocalDateTime): LocalDateTime? {
        var candidate = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = candidate.plusDays(366)
        while (candidate.isBefore(limit)) {
            if (matches(candidate)) return candidate
            candidate = candidate.plusMinutes(1)
        }
        return null
    }

    companion object {
        fun parse(expression: String): CronSchedule {
            val fields = expression.trim().split(Regex("\\s+"))
            require(fields.size == 5) { "Cron expression must contain exactly five fields" }
            return CronSchedule(
                parseField(fields.getOrNull(0) ?: error("Missing minute field"), 0, 59),
                parseField(fields.getOrNull(1) ?: error("Missing hour field"), 0, 23),
                parseField(fields.getOrNull(2) ?: error("Missing day field"), 1, 31),
                parseField(fields.getOrNull(3) ?: error("Missing month field"), 1, 12),
                parseField(fields.getOrNull(4) ?: error("Missing weekday field"), 0, 7)
            )
        }

        private fun parseField(text: String, min: Int, max: Int): Set<Int> {
            val result = sortedSetOf<Int>()
            for (piece in text.split(',')) {
                require(piece.isNotBlank()) { "Empty cron list item" }
                val sides = piece.split('/')
                require(sides.size <= 2) { "Invalid step: $piece" }
                val step = if (sides.size == 2) sides.getOrNull(1)?.toIntOrNull()
                    ?: error("Invalid step: $piece") else 1
                require(step > 0) { "Cron step must be positive: $piece" }
                val bounds = sides.getOrNull(0) ?: error("Invalid value: $piece")
                val start: Int
                val end: Int
                when {
                    bounds == "*" -> { start = min; end = max }
                    bounds.contains('-') -> {
                        val range = bounds.split('-')
                        require(range.size == 2) { "Invalid range: $piece" }
                        start = range.getOrNull(0)?.toIntOrNull() ?: error("Invalid range: $piece")
                        end = range.getOrNull(1)?.toIntOrNull() ?: error("Invalid range: $piece")
                    }
                    else -> {
                        start = bounds.toIntOrNull() ?: error("Invalid value: $piece")
                        end = if (sides.size == 2) max else start
                    }
                }
                require(start in min..max && end in min..max && start <= end) { "Value out of range: $piece" }
                var value = start
                while (value <= end) { result += value; value += step }
            }
            return result
        }
    }
}
