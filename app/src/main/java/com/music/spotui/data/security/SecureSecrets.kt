package com.music.spotui.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

/** Android Keystore-backed, per-install secret storage with one-time legacy migration. */
internal object SecureSecrets {
    private const val ALIAS = "guntify.session.aes.v1"
    private const val STORE = "guntify_secure_secrets"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized fun read(context: Context, legacyStore: String, legacyKey: String): String? {
        val id = "$legacyStore:$legacyKey"
        val secure = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val envelope = secure.getString(id, null)
        if (envelope != null) {
            // Never fall back to old plaintext when decryption fails.
            val parts = SecretEnvelope.unpack(envelope) ?: return null
            return runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, parts.first))
                String(cipher.doFinal(parts.second), Charsets.UTF_8)
            }.getOrNull()
        }
        val legacy = context.getSharedPreferences(legacyStore, Context.MODE_PRIVATE)
        val value = legacy.getString(legacyKey, null)?.takeIf(String::isNotBlank) ?: return null
        write(context, legacyStore, legacyKey, value) // only delete plaintext after successful commit
        return value
    }

    @Synchronized fun write(context: Context, legacyStore: String, legacyKey: String, value: String?) {
        val id = "$legacyStore:$legacyKey"
        val secure = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences(legacyStore, Context.MODE_PRIVATE)
        if (value.isNullOrEmpty()) {
            check(secure.edit().remove(id).commit())
            check(legacy.edit().remove(legacyKey).commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = SecretEnvelope.pack(cipher.iv, cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        check(secure.edit().putString(id, sealed).commit()) { "Could not safely save encrypted session" }
        check(legacy.edit().remove(legacyKey).commit()) { "Could not erase legacy session" }
    }
}

/** Testable payload framing, without keys or Android APIs. */
internal object SecretEnvelope {
    fun pack(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size == 12 && ciphertext.size >= 16)
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }
    fun unpack(value: String): Pair<ByteArray, ByteArray>? = runCatching {
        val data = Base64.getDecoder().decode(value)
        require(data.size in 28..65536)
        data.copyOfRange(0, 12) to data.copyOfRange(12, data.size)
    }.getOrNull()
}
