package com.example.m_nirog.core

import android.content.Context

/**
 * Remembers that the user logged in and accepted the consent text.
 * Stub only: Slice 2 keeps the real access and refresh tokens in encrypted storage.
 * Never store phone numbers or health data here.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getBoolean("logged_in", false)
    fun setLoggedIn(value: Boolean) = prefs.edit().putBoolean("logged_in", value).apply()

    fun consentVersion(): String? = prefs.getString("consent_version", null)
    fun saveConsent(version: String) = prefs.edit().putString("consent_version", version).apply()

    fun clear() = prefs.edit().clear().apply()
}
