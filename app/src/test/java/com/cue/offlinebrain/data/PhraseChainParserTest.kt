package com.cue.offlinebrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseChainParserTest {
    @Test fun preservesOrderAndSkipsBlankLines() {
        assertEquals(listOf("device status", "set alarm for 6 am", "open settings"), PhraseChainParser.parse(" device status \n\nset alarm for 6 am\nopen settings"))
    }

    @Test fun limitsStepCountAndCommandLength() {
        val parsed = PhraseChainParser.parse((1..20).joinToString("\n") { "step $it " + "x".repeat(600) })
        assertEquals(PhraseChainParser.MAX_STEPS, parsed.size)
        assertTrue(parsed.all { it.length == PhraseChainParser.MAX_COMMAND_LENGTH })
    }

    @Test fun previewIsNumberedAndDoesNotExecuteCommands() {
        val preview = PhraseChainParser.preview("device status\nopen settings")
        assertEquals("1. device status\n2. open settings", preview)
        assertTrue(PhraseChainParser.preview("\n ").contains("No actions yet"))
    }
}
