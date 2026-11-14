package com.cue.offlinebrain.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SecurityPrimitivesTest {
    @Test fun pinVerifierUsesSaltAndRejectsWrongPin() {
        val first = PinHasher.create("123456")
        val second = PinHasher.create("123456")
        assertTrue(PinHasher.verify("123456", first))
        assertFalse(PinHasher.verify("654321", first))
        assertFalse("Each verifier should get a fresh random salt", first == second)
    }

    @Test fun backupRoundTripsAndRejectsWrongPassphrase() {
        val source = "private offline records".toByteArray(StandardCharsets.UTF_8)
        val encrypted = PortableBackupCrypto.encrypt("correct horse battery", source)
        assertArrayEquals(source, PortableBackupCrypto.decrypt("correct horse battery", encrypted))
        assertFalse(encrypted.contentEquals(source))
        runCatching { PortableBackupCrypto.decrypt("wrong passphrase", encrypted) }
            .onSuccess { throw AssertionError("Wrong passphrase must not decrypt the file") }
    }

    @Test fun backupRejectsTampering() {
        val encrypted = PortableBackupCrypto.encrypt("correct horse battery", byteArrayOf(1, 2, 3, 4, 5))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        runCatching { PortableBackupCrypto.decrypt("correct horse battery", encrypted) }
            .onSuccess { throw AssertionError("Modified ciphertext must fail authentication") }
    }

    @Test fun pinPolicyRequiresSixToTwelveDigits() {
        assertFalse(runCatching { PinHasher.create("12345") }.isSuccess)
        assertFalse(runCatching { PinHasher.create("12a456") }.isSuccess)
        assertFalse(runCatching { PinHasher.create("1234567890123") }.isSuccess)
    }
}
