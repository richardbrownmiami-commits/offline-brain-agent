package com.cue.offlinebrain.device

import kotlin.math.roundToInt

/** Converts typed commands to a target within Android's media-stream range. */
object VolumeLevelPolicy {
    fun target(current: Int, minimum: Int, maximum: Int, command: VolumeCommand): Int? {
        if (minimum < 0 || maximum < minimum) return null
        val bounded = current.coerceIn(minimum, maximum)
        return when (command) {
            VolumeCommand.IncreaseOneStep -> (bounded + 1).coerceAtMost(maximum)
            VolumeCommand.DecreaseOneStep -> (bounded - 1).coerceAtLeast(minimum)
            VolumeCommand.Mute -> minimum
            is VolumeCommand.SetPercent -> (minimum + (maximum - minimum) * command.percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(minimum, maximum)
            VolumeCommand.Read, VolumeCommand.NotCommand -> null
        }
    }

    fun percentage(current: Int, minimum: Int, maximum: Int): Int? {
        if (minimum < 0 || maximum <= minimum) return null
        return (((current.coerceIn(minimum, maximum) - minimum) * 100.0) / (maximum - minimum)).roundToInt().coerceIn(0, 100)
    }
}
