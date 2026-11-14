package com.cue.offlinebrain.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroCaptureSessionTest {
    @Test fun capturesOnlyDuringAnExplicitSessionAndClearsOnStop() {
        MacroCaptureSession.stopAndTake()
        MacroCaptureSession.append("outside session")
        assertTrue(MacroCaptureSession.steps.value.isEmpty())

        MacroCaptureSession.start()
        MacroCaptureSession.append("Open WhatsApp")
        MacroCaptureSession.append("Tap Search")
        assertTrue(MacroCaptureSession.isActive)
        assertEquals(listOf("Open WhatsApp", "Tap Search"), MacroCaptureSession.stopAndTake())
        assertFalse(MacroCaptureSession.isActive)
        assertTrue(MacroCaptureSession.steps.value.isEmpty())
        MacroCaptureSession.append("after stop")
        assertTrue(MacroCaptureSession.steps.value.isEmpty())
    }

    @Test fun sessionKeepsAtMostFiftySteps() {
        MacroCaptureSession.start()
        repeat(55) { MacroCaptureSession.append("step $it") }
        assertEquals(50, MacroCaptureSession.steps.value.size)
        assertEquals("step 49", MacroCaptureSession.stopAndTake().last())
    }
}
