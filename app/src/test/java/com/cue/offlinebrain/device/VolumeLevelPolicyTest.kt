package com.cue.offlinebrain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeLevelPolicyTest {
    @Test fun percentMapsAcrossDeviceRangeAndClamps() {
        assertEquals(0, VolumeLevelPolicy.target(5, 0, 15, VolumeCommand.SetPercent(0)))
        assertEquals(8, VolumeLevelPolicy.target(5, 0, 15, VolumeCommand.SetPercent(50)))
        assertEquals(15, VolumeLevelPolicy.target(5, 0, 15, VolumeCommand.SetPercent(100)))
        assertEquals(0, VolumeLevelPolicy.target(5, 0, 15, VolumeCommand.SetPercent(-4)))
    }

    @Test fun oneStepAndMuteNeverEscapeMediaRange() {
        assertEquals(15, VolumeLevelPolicy.target(15, 0, 15, VolumeCommand.IncreaseOneStep))
        assertEquals(0, VolumeLevelPolicy.target(0, 0, 15, VolumeCommand.DecreaseOneStep))
        assertEquals(0, VolumeLevelPolicy.target(12, 0, 15, VolumeCommand.Mute))
    }

    @Test fun readAndInvalidDeviceRangesDoNotChangeVolume() {
        assertNull(VolumeLevelPolicy.target(5, 0, 15, VolumeCommand.Read))
        assertNull(VolumeLevelPolicy.target(5, 10, 5, VolumeCommand.Mute))
        assertEquals(73, VolumeLevelPolicy.percentage(11, 0, 15))
        assertNull(VolumeLevelPolicy.percentage(1, 0, 0))
    }
}
