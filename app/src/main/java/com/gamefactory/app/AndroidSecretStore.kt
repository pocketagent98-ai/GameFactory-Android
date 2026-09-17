package com.gamefactory.app

import android.content.Context
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal encrypted secret store backed by the Android Keystore.
 * API keys never sit in SharedPreferences in plain text and never
 * appear in source code or logs - they are entered by the user at
 * runtime and encrypted with an AES/GCM key held by the Keystore.
 */
class AndroidSecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("gf_secrets", Context.MODE_PRIVATE)
    private val keystoreAlias = "gamefactory_master"

    private fun masterKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            keystoreAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(encrypted, 0, blob, iv.size, encrypted.size)
        prefs.edit().putString(name, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val encrypted = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun clear(name: String) { prefs.edit().remove(name).apply() }

    companion object {
        const val KEY_GEMINI = "GEMINI_API_KEY"
        const val KEY_MODEL = "GEMINI_MODEL"
        const val PREF_DEMO_MODE = "demo_mode"

        fun randomId(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            return (1..5).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }
    }
}
