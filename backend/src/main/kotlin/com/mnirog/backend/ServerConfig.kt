package com.mnirog.backend

object ServerConfig {
    const val OTP_TTL_MS = 5 * 60_000L
    const val OTP_RESEND_COOLDOWN_MS = 60_000L
    const val OTP_SEND_WINDOW_MS = 15 * 60_000L
    const val OTP_MAX_SENDS_PER_WINDOW = 5
    const val OTP_MAX_VERIFY_ATTEMPTS = 5
    const val OTP_LOCKOUT_MS = 15 * 60_000L

    fun isDevRoutesEnabled(rawEnv: String? = System.getenv("ENABLE_DEV_ROUTES")): Boolean =
        rawEnv?.trim()?.equals("true", ignoreCase = true) == true

    val enableDevRoutes: Boolean
        get() = isDevRoutesEnabled()

    val smsProviderPreference: String?
        get() = System.getenv("SMS_PROVIDER")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    val sparrowToken: String?
        get() = System.getenv("SPARROW_SMS_TOKEN")?.trim()?.takeIf { it.isNotBlank() }

    val sparrowFrom: String
        get() = System.getenv("SPARROW_SMS_FROM")?.trim()?.takeIf { it.isNotBlank() } ?: "TheAlert"

    val sparrowUrl: String
        get() = System.getenv("SPARROW_SMS_URL")?.trim()?.takeIf { it.isNotBlank() }
            ?: "https://api.sparrowsms.com/v2/sms/"

    val aakashToken: String?
        get() = System.getenv("AAKASH_SMS_AUTH_TOKEN")?.trim()?.takeIf { it.isNotBlank() }

    val aakashUrl: String
        get() = System.getenv("AAKASH_SMS_URL")?.trim()?.takeIf { it.isNotBlank() }
            ?: "https://sms.aakashsms.com/sms/v3/send"
}
