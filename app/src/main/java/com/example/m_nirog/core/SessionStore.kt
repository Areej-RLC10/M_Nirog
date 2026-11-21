package com.example.m_nirog.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AuthTokenStore {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun role(): String
    fun saveTokens(accessToken: String, refreshToken: String)
    fun saveRole(role: String)
    fun clear()
}

class SessionStore(context: Context) : AuthTokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getBoolean("logged_in", false)
    fun setLoggedIn(value: Boolean) = prefs.edit().putBoolean("logged_in", value).apply()

    fun consentVersion(): String? = prefs.getString("consent_version", null)
    fun saveConsent(version: String) = prefs.edit().putString("consent_version", version).apply()

    override fun role(): String = prefs.getString("role", "PATIENT") ?: "PATIENT"
    override fun saveRole(role: String) = prefs.edit().putString("role", role).apply()

    override fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("access_enc", encrypt(accessToken))
            .putString("refresh_enc", encrypt(refreshToken))
            .apply()
    }

    override fun accessToken(): String? = prefs.getString("access_enc", null)?.let(::decrypt)
    override fun refreshToken(): String? = prefs.getString("refresh_enc", null)?.let(::decrypt)

    override fun clear() = prefs.edit().clear().apply()

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@runCatching existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val key = getOrCreateKey() ?: return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val key = getOrCreateKey() ?: return@runCatching null
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH) return@runCatching null
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "m_nirog_session_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
