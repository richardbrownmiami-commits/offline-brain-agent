package com.cue.offlinebrain.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRunPolicyTest {
    @Test fun stopsForRecognizedCommandFailures() {
        assertTrue(MacroRunPolicy.isFailureReply("I couldn't safely plan that command. Nothing was run."))
        assertTrue(MacroRunPolicy.isFailureReply("FAIL — network request failed."))
        assertTrue(MacroRunPolicy.isFailureReply("Android could not open that Settings page."))
        assertTrue(MacroRunPolicy.isFailureReply("Permission required to use Accessibility."))
    }

    @Test fun continuesForNormalAnswersAndSuccessfulActions() {
        assertFalse(MacroRunPolicy.isFailureReply("Weather in Delhi: sunny."))
        assertFalse(MacroRunPolicy.isFailureReply("Reminder set for 7:30 AM."))
        assertFalse(MacroRunPolicy.isFailureReply("Flashlight turned on."))
        assertFalse(MacroRunPolicy.isFailureReply("The next step is ready."))
    }
}
