package com.cue.offlinebrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedMacroStepsTest {
    @Test fun roundTripsEveryStepVariant() {
        val steps = listOf(
            TypedMacroStep.OpenApp("Calendar"),
            TypedMacroStep.TapText("Save"),
            TypedMacroStep.TypeText("hello, 世界\nagain"),
            TypedMacroStep.Wait(300),
            TypedMacroStep.Scroll,
            TypedMacroStep.Back,
            TypedMacroStep.Home,
            TypedMacroStep.Recents,
            TypedMacroStep.SpeakText("Done"),
            TypedMacroStep.RunChatCommand("device status"),
            TypedMacroStep.DeviceStatus,
            TypedMacroStep.RunAnotherMacro(42L)
        )
        assertEquals(steps, TypedMacroSteps.decode(TypedMacroSteps.encode(steps)))
    }

    @Test fun legacyLinesBecomeOrderedChatCommands() {
        assertEquals(
            listOf(
                TypedMacroStep.RunChatCommand("open calendar"),
                TypedMacroStep.RunChatCommand("device status")
            ),
            TypedMacroSteps.decode(" open calendar \n\n device status \n")
        )
    }

    @Test fun preservesTypedStepOrder() {
        val steps = listOf(
            TypedMacroStep.Home,
            TypedMacroStep.RunChatCommand("first"),
            TypedMacroStep.Wait(0),
            TypedMacroStep.RunChatCommand("last")
        )
        assertEquals(steps, TypedMacroSteps.decode(TypedMacroSteps.encode(steps)))
    }

    @Test fun rejectsUnknownVersionsMalformedPayloadsAndBounds() {
        assertRejected { TypedMacroSteps.decode("OBMS:2:AAAA") }
        assertRejected { TypedMacroSteps.decode("OBMS:1:not-base64!") }
        assertRejected { TypedMacroSteps.encode(List(51) { TypedMacroStep.Back }) }
        assertRejected { TypedMacroSteps.encode(listOf(TypedMacroStep.TypeText("x".repeat(501)))) }
        assertRejected { TypedMacroSteps.encode(listOf(TypedMacroStep.Wait(-1))) }
        assertRejected { TypedMacroSteps.encode(listOf(TypedMacroStep.Wait(301))) }
        assertRejected { TypedMacroSteps.encode(listOf(TypedMacroStep.RunAnotherMacro(0))) }
    }

    @Test fun legacyInputHonorsStepAndPayloadCaps() {
        val many = (1..60).joinToString("\n") { "command $it" }
        val decoded = TypedMacroSteps.decode(many)
        assertEquals(TypedMacroSteps.MAX_STEPS, decoded.size)
        val longCommand = TypedMacroSteps.decode("z".repeat(600)).single() as TypedMacroStep.RunChatCommand
        assertEquals(TypedMacroSteps.MAX_PAYLOAD_LENGTH, longCommand.command.length)
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected IllegalArgumentException", rejected)
    }
}
