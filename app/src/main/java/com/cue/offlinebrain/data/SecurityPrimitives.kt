package com.cue.offlinebrain.data

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import com.cue.offlinebrain.diagnostics.CrashLog
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun create(pin: String): String {
        require(pin.matches(Regex("[0-9]{6,12}"))) { "Use a PIN with 6 to 12 digits." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        return "v1:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derive(pin, salt))}"
    }

    fun verify(pin: String, encoded: String): Boolean = try {
        val parts = encoded.split(':')
        if (parts.size != 3 || parts.getOrNull(0) != "v1") return false
        val saltText = parts.getOrNull(1) ?: return false
        val expectedText = parts.getOrNull(2) ?: return false
        val salt = Base64.getDecoder().decode(saltText)
        val expected = Base64.getDecoder().decode(expectedText)
        MessageDigest.isEqual(expected, derive(pin, salt))
    } catch (error: Exception) {
        CrashLog.record("SECURITY", "PIN verifier could not be parsed", error)
        false
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

object PinLockStore {
    private const val SECRET_KEY = "app_pin_hash"
    fun hasPin(context: Context): Boolean = try {
        KeystoreSecretStore.get(context, SECRET_KEY) != null
    } catch (error: Exception) {
        CrashLog.record("SECURITY", "saved app PIN state could not be read", error)
        false
    }
    fun setPin(context: Context, pin: String) { KeystoreSecretStore.put(context, SECRET_KEY, PinHasher.create(pin)) }
    fun verify(context: Context, pin: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(ATTEMPTS_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (prefs.getLong(LOCKOUT_UNTIL, 0L) > now) return false
        val matches = try {
            KeystoreSecretStore.get(context, SECRET_KEY)?.let { PinHasher.verify(pin, it) } ?: false
        } catch (error: Exception) {
            CrashLog.record("SECURITY", "app PIN verification failed", error)
            false
        }
        val edit = prefs.edit()
        if (matches) {
            edit.remove(FAILURES).remove(LOCKOUT_UNTIL)
        } else {
            val failures = prefs.getInt(FAILURES, 0) + 1
            if (failures >= MAX_FAILURES) edit.putInt(FAILURES, 0).putLong(LOCKOUT_UNTIL, now + LOCKOUT_MILLIS)
            else edit.putInt(FAILURES, failures)
        }
        if (!edit.commit()) CrashLog.record("SECURITY", "PIN attempt counter could not be saved")
        return matches
    }
    fun clear(context: Context) {
        KeystoreSecretStore.remove(context, SECRET_KEY)
        context.applicationContext.getSharedPreferences(ATTEMPTS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private const val ATTEMPTS_FILE = "app_pin_attempts"
    private const val FAILURES = "failures"
    private const val LOCKOUT_UNTIL = "lockout_until"
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MILLIS = 30_000L
}

/** Synchronous mirror prevents the app from briefly drawing private screens while DataStore loads. */
object AppLockStateStore {
    private const val FILE_NAME = "app_lock_state"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
    private const val KEY_INITIALIZED = "initialized"

    fun isInitialized(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getBoolean(KEY_INITIALIZED, false)

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val saved = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).putBoolean(KEY_INITIALIZED, true).commit()
        check(saved) { "Android could not persist the app-lock state" }
    }

    fun timeoutSeconds(context: Context): Int = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_TIMEOUT_SECONDS, AppLockPolicy.DEFAULT_TIMEOUT_SECONDS)
        .let(AppLockPolicy::normalizeTimeoutSeconds)

    fun setTimeoutSeconds(context: Context, seconds: Int) {
        val saved = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TIMEOUT_SECONDS, AppLockPolicy.normalizeTimeoutSeconds(seconds)).commit()
        check(saved) { "Android could not persist the app-lock delay" }
    }
}

object PortableBackupCrypto {
    private val magic = byteArrayOf('C'.code.toByte(), 'U'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 1)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 240_000

    fun encrypt(passphrase: String, plaintext: ByteArray): ByteArray {
        require(passphrase.length >= 10) { "Use a backup passphrase with at least 10 characters." }
        require(plaintext.size <= 32 * 1024 * 1024) { "Backup is too large (maximum 32 MB)." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(magic)
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(magic.size + SALT_BYTES + IV_BYTES + encrypted.size)
            .put(magic).put(salt).put(iv).put(encrypted).array()
    }

    fun decrypt(passphrase: String, file: ByteArray): ByteArray {
        require(passphrase.length >= 10) { "Enter the backup passphrase used when exporting." }
        require(file.size in (magic.size + SALT_BYTES + IV_BYTES + 16)..(32 * 1024 * 1024 + 128)) { "This backup file is empty, invalid, or larger than 32 MB." }
        val buffer = ByteBuffer.wrap(file)
        val header = ByteArray(magic.size).also(buffer::get)
        require(header.contentEquals(magic)) { "This is not a supported Cue encrypted backup." }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(magic)
        return try { cipher.doFinal(ciphertext) } catch (error: Exception) {
            throw IllegalArgumentException("Could not decrypt this backup. Check the passphrase or file integrity.", error)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
}
