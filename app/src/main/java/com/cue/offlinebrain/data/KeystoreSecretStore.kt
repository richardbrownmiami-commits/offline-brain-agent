package com.cue.offlinebrain.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cue.offlinebrain.diagnostics.CrashLog
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted-value store using platform Android Keystore AES-GCM. */
object KeystoreSecretStore {
    private const val KEY_ALIAS = "offline_brain_secret_store_v1"
    private const val PREFS_NAME = "brain_keystore_secrets"
    private const val GCM_TAG_BITS = 128

    fun get(context: Context, key: String): String? {
        val packed = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            if (bytes.size < 2) throw IllegalStateException("Encrypted value is too short")
            val buffer = ByteBuffer.wrap(bytes)
            val ivSize = buffer.get().toInt() and 0xff
            if (ivSize !in 12..16 || buffer.remaining() <= ivSize) throw IllegalStateException("Encrypted value has an invalid IV")
            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            CrashLog.record("SECRETS", "encrypted value could not be opened key=$key", error)
            throw IllegalStateException("A saved encrypted value could not be opened. Existing data was not replaced.", error)
        }
    }

    fun put(context: Context, key: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val packed = ByteBuffer.allocate(1 + iv.size + ciphertext.size).put(iv.size.toByte()).put(iv).put(ciphertext).array()
            val stored = Base64.encodeToString(packed, Base64.NO_WRAP)
            val saved = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(key, stored).commit()
            check(saved) { "Android could not persist the encrypted value" }
        } catch (error: Exception) {
            CrashLog.record("SECRETS", "encrypted value could not be saved key=$key", error)
            throw IllegalStateException("Android could not securely save this value.", error)
        }
    }

    fun remove(context: Context, key: String) {
        runCatching { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(key).commit() }
            .onFailure { CrashLog.record("SECRETS", "encrypted value could not be removed key=$key", it) }
    }

    /** Reads and immediately re-encrypts one value written by the prior EncryptedSharedPreferences store. */
    fun migrateLegacy(context: Context, legacyFile: String, legacyKey: String, newKey: String): String? {
        val legacy = legacyPreferences(context, legacyFile)
        val value = legacy.getString(legacyKey, null) ?: return null
        put(context, newKey, value)
        legacy.edit().remove(legacyKey).apply()
        CrashLog.record("SECRETS", "legacy encrypted value migrated key=$newKey")
        return value
    }

    fun removeLegacy(context: Context, legacyFile: String, legacyKey: String) {
        runCatching { legacyPreferences(context, legacyFile).edit().remove(legacyKey).commit() }
            .onFailure { CrashLog.record("SECRETS", "legacy encrypted value could not be removed key=$legacyKey", it) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun legacyPreferences(context: Context, fileName: String) = EncryptedSharedPreferences.create(
        context.applicationContext,
        fileName,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
