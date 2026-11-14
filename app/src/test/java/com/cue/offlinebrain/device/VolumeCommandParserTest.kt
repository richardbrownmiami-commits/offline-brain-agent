package com.cue.offlinebrain.device

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeCommandParserTest {
    @Test fun parsesStepMuteReadAndPunctuationVariants() {
        assertEquals(VolumeCommand.IncreaseOneStep, VolumeCommandParser.parse("volume up"))
        assertEquals(VolumeCommand.IncreaseOneStep, VolumeCommandParser.parse("RISE, VOLUME!"))
        assertEquals(VolumeCommand.DecreaseOneStep, VolumeCommandParser.parse("Volume down."))
        assertEquals(VolumeCommand.DecreaseOneStep, VolumeCommandParser.parse("lower volume"))
        assertEquals(VolumeCommand.Mute, VolumeCommandParser.parse("MUTE!!!"))
        assertEquals(VolumeCommand.Mute, VolumeCommandParser.parse("volume mute"))
        assertEquals(VolumeCommand.Read, VolumeCommandParser.parse("read volume?"))
    }

    @Test fun numericRiseIsExplicitlyTargetPercentAndSetFormsWork() {
        assertEquals(VolumeCommand.SetPercent(30), VolumeCommandParser.parse("rise volume 30"))
        assertEquals(VolumeCommand.SetPercent(50), VolumeCommandParser.parse("set volume to 50 percent"))
        assertEquals(VolumeCommand.SetPercent(45), VolumeCommandParser.parse("SET VOLUME 45%"))
        assertEquals(VolumeCommand.SetPercent(0), VolumeCommandParser.parse("rise volume 0"))
        assertEquals(VolumeCommand.SetPercent(100), VolumeCommandParser.parse("set volume to 100 percent"))
    }

    @Test fun permitsOneEditTypoOnlyInKnownCommandTokens() {
        assertEquals(VolumeCommand.IncreaseOneStep, VolumeCommandParser.parse("volum up"))
        assertEquals(VolumeCommand.SetPercent(30), VolumeCommandParser.parse("rise volme 30"))
        assertEquals(VolumeCommand.Mute, VolumeCommandParser.parse("mut"))
        // At most one edit: a two-edit error is rejected.
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("volumeee up"))
    }

    @Test fun rejectsOutOfRangeSignedMultipleAndConflictingNumbers() {
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("set volume 101 percent"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("rise volume -1"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("rise volume 30 40"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("set volume to 30 percent and 40"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("set volume 999999999999999999999"))
    }

    @Test fun rejectsUnrelatedSentencesAndAmbiguousInputs() {
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("please turn the volume up now"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("the volume is up"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("play music at volume 30"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("set volume to percent 30"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("rise volume maybe"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("set volume to 50 percent mute"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse("volume up down"))
        assertEquals(VolumeCommand.NotCommand, VolumeCommandParser.parse(""))
    }
}
