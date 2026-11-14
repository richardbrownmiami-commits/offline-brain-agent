package com.cue.offlinebrain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechInputStateMachineTest {
    @Test fun startsIdleThenListensWithTwelveSecondLimit() {
        val machine = SpeechInputStateMachine()
        assertEquals(SpeechPhase.IDLE, machine.state.phase)
        val listening = machine.start()
        assertEquals(SpeechPhase.LISTENING, listening.phase)
        assertEquals(12, listening.secondsRemaining)
        assertEquals(0, machine.tick(-2).secondsRemaining)
        assertEquals(12, machine.tick(99).secondsRemaining)
    }

    @Test fun partialAndManualStopPreserveFinalText() {
        val machine = SpeechInputStateMachine()
        machine.start()
        machine.partial("open the settings")
        val processing = machine.stopRequested()
        assertEquals(SpeechPhase.PROCESSING, processing.phase)
        val result = machine.result("")
        assertEquals(SpeechPhase.RESULT, result.phase)
        assertEquals("open the settings", result.finalText)
    }

    @Test fun recognizedResultReplacesPartialTranscript() {
        val machine = SpeechInputStateMachine()
        machine.start()
        machine.partial("play")
        assertEquals("play music", machine.result(" play music ").finalText)
    }

    @Test fun emptyFinalTextProducesReadableRetryError() {
        val machine = SpeechInputStateMachine()
        machine.start()
        val error = machine.result("")
        assertEquals(SpeechPhase.ERROR, error.phase)
        assertTrue(error.message.contains("did not hear anything", ignoreCase = true))
    }

    @Test fun platformErrorIsVisibleAndResetReturnsToIdle() {
        val machine = SpeechInputStateMachine()
        machine.start()
        val error = machine.error("Microphone permission is missing.")
        assertEquals(SpeechPhase.ERROR, error.phase)
        assertEquals("Microphone permission is missing.", error.message)
        assertEquals(SpeechPhase.IDLE, machine.reset().phase)
    }

    @Test fun resultTextIsBoundedAndCallbacksAfterStopDoNotChangePartial() {
        val machine = SpeechInputStateMachine()
        machine.start()
        machine.partial("A".repeat(3_000))
        assertEquals(2_000, machine.state.partialText.length)
        machine.stopRequested()
        assertEquals(machine.state, machine.partial("late callback"))
    }
}
