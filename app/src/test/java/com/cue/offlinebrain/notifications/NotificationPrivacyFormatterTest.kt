package com.cue.offlinebrain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyFormatterTest {
    @Test fun callsAndSmsBodiesStayHiddenByDefault() {
        assertEquals("Asha", NotificationPrivacyFormatter.line("Calls", "Asha", "missed at 9:10"))
        assertEquals("Asha", NotificationPrivacyFormatter.line("Messages/SMS", "Asha", "Hi there"))
        assertEquals("Asha: Hi there", NotificationPrivacyFormatter.line("Messages/SMS", "Asha", "Hi there", includeMessageBody = true))
    }

    @Test fun masksDigitsOnlyWhenAContextualCodeLabelIsPresent() {
        val text = "Your verification code is 482913. It expires soon."
        assertTrue(NotificationPrivacyFormatter.containsOtpCode(text))
        assertEquals("Your verification code is digits hidden. It expires soon.", NotificationPrivacyFormatter.maskOtpCodes(text))
        assertEquals("Order 482913 is ready", NotificationPrivacyFormatter.maskOtpCodes("Order 482913 is ready"))
        assertFalse(NotificationPrivacyFormatter.containsOtpCode("Order 482913 is ready"))
    }
}
