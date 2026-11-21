package com.mnirog.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.ceil

data class OtpRecordState(
    val codeHash: String,
    val expiresAtMs: Long,
    val attempts: Int,
    val sendCount: Int,
    val windowStartMs: Long,
    val lastSentAtMs: Long,
    val lockedUntilMs: Long,
)

sealed interface OtpSendEligibility {
    data class Allowed(
        val nextSendCount: Int,
        val nextWindowStartMs: Long,
    ) : OtpSendEligibility

    data class Blocked(
        val status: HttpStatusCode,
        val error: ErrorResponse,
    ) : OtpSendEligibility
}

sealed interface OtpVerifyEvaluation {
    data object Verified : OtpVerifyEvaluation

    data class Failed(
        val status: HttpStatusCode,
        val error: ErrorResponse,
        val nextAttempts: Int?,
        val nextLockedUntilMs: Long?,
    ) : OtpVerifyEvaluation
}

sealed interface SmsDispatchResult {
    data class Sent(val devOtp: String? = null) : SmsDispatchResult
    data class Error(val status: HttpStatusCode, val error: ErrorResponse) : SmsDispatchResult
}

object OtpService {
    private val secureRandom = SecureRandom()
    private val smsHttp = HttpClient(CIO)
    private val smsJson = Json { ignoreUnknownKeys = true }
    private val nepalPhoneRegex = Regex("^\\+9779\\d{9}$")

    fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("977") -> "+$digits"
            digits.length == 11 && digits.startsWith("09") -> "+977${digits.drop(1)}"
            digits.length == 10 && digits.startsWith("9") -> "+977$digits"
            else -> "+$digits"
        }
    }

    fun isValidNepalPhone(normalizedPhone: String): Boolean =
        nepalPhoneRegex.matches(normalizedPhone)

    fun toLocalTenDigitPhone(normalizedPhone: String): String =
        normalizedPhone.removePrefix("+977")

    fun generateCode(): String =
        (100_000 + secureRandom.nextInt(900_000)).toString()

    fun hashOtp(phone: String, code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$phone:${code.trim()}".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyOtpHash(phone: String, rawCode: String, expectedHex: String): Boolean {
        val actual = hashOtp(phone, rawCode).toByteArray(StandardCharsets.UTF_8)
        val expected = expectedHex.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(actual, expected)
    }

    fun evaluateSendEligibility(
        existing: OtpRecordState?,
        nowMs: Long,
    ): OtpSendEligibility {
        if (existing != null && existing.lockedUntilMs > nowMs) {
            val waitMin = ceil((existing.lockedUntilMs - nowMs) / 60_000.0).toInt().coerceAtLeast(1)
            return OtpSendEligibility.Blocked(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("OTP_LOCKED", "Too many failed attempts. Try again in $waitMin minute(s)."),
            )
        }
        if (existing != null && existing.lastSentAtMs > 0L) {
            val elapsed = nowMs - existing.lastSentAtMs
            if (elapsed in 0 until ServerConfig.OTP_RESEND_COOLDOWN_MS) {
                val waitSec = ceil((ServerConfig.OTP_RESEND_COOLDOWN_MS - elapsed) / 1000.0).toInt().coerceAtLeast(1)
                return OtpSendEligibility.Blocked(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("OTP_COOLDOWN", "Please wait ${waitSec}s before requesting another code."),
                )
            }
        }
        val withinWindow = existing != null &&
            existing.windowStartMs > 0L &&
            (nowMs - existing.windowStartMs) < ServerConfig.OTP_SEND_WINDOW_MS

        return if (withinWindow) {
            if (existing!!.sendCount >= ServerConfig.OTP_MAX_SENDS_PER_WINDOW) {
                OtpSendEligibility.Blocked(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("OTP_RATE_LIMITED", "Too many OTP requests. Please try again later."),
                )
            } else {
                OtpSendEligibility.Allowed(
                    nextSendCount = existing.sendCount + 1,
                    nextWindowStartMs = existing.windowStartMs,
                )
            }
        } else {
            OtpSendEligibility.Allowed(
                nextSendCount = 1,
                nextWindowStartMs = nowMs,
            )
        }
    }

    fun evaluateVerifyAttempt(
        existing: OtpRecordState?,
        codeMatched: Boolean,
        nowMs: Long,
    ): OtpVerifyEvaluation {
        if (existing == null) {
            return OtpVerifyEvaluation.Failed(
                status = HttpStatusCode.Unauthorized,
                error = ErrorResponse("INVALID_OTP", "Invalid or expired OTP code"),
                nextAttempts = null,
                nextLockedUntilMs = null,
            )
        }
        if (existing.lockedUntilMs > nowMs) {
            val waitMin = ceil((existing.lockedUntilMs - nowMs) / 60_000.0).toInt().coerceAtLeast(1)
            return OtpVerifyEvaluation.Failed(
                status = HttpStatusCode.TooManyRequests,
                error = ErrorResponse("OTP_LOCKED", "Too many failed attempts. Try again in $waitMin minute(s)."),
                nextAttempts = null,
                nextLockedUntilMs = null,
            )
        }
        if (existing.expiresAtMs < nowMs) {
            return OtpVerifyEvaluation.Failed(
                status = HttpStatusCode.Unauthorized,
                error = ErrorResponse("INVALID_OTP", "OTP code has expired. Please request a new one."),
                nextAttempts = null,
                nextLockedUntilMs = null,
            )
        }
        if (codeMatched) {
            return OtpVerifyEvaluation.Verified
        }
        val nextAttempts = existing.attempts + 1
        return if (nextAttempts >= ServerConfig.OTP_MAX_VERIFY_ATTEMPTS) {
            val lockUntil = nowMs + ServerConfig.OTP_LOCKOUT_MS
            OtpVerifyEvaluation.Failed(
                status = HttpStatusCode.TooManyRequests,
                error = ErrorResponse("OTP_LOCKED", "Too many failed attempts. Account locked for 15 minutes."),
                nextAttempts = nextAttempts,
                nextLockedUntilMs = lockUntil,
            )
        } else {
            val remaining = ServerConfig.OTP_MAX_VERIFY_ATTEMPTS - nextAttempts
            OtpVerifyEvaluation.Failed(
                status = HttpStatusCode.Unauthorized,
                error = ErrorResponse("INVALID_OTP", "Invalid OTP code ($remaining attempt(s) remaining)"),
                nextAttempts = nextAttempts,
                nextLockedUntilMs = null,
            )
        }
    }

    suspend fun sendOtp(rawPhone: String): Pair<HttpStatusCode, Any> {
        val phone = normalizePhone(rawPhone)
        if (!isValidNepalPhone(phone)) {
            return HttpStatusCode.BadRequest to ErrorResponse("INVALID_PHONE", "Enter a valid 10-digit Nepal mobile number.")
        }
        val now = System.currentTimeMillis()

        val existing = Database.useConnection { conn -> loadOtpState(conn, phone) }
        val eligibility = evaluateSendEligibility(existing, now)
        if (eligibility is OtpSendEligibility.Blocked) {
            return eligibility.status to eligibility.error
        }
        val allowed = eligibility as OtpSendEligibility.Allowed

        val code = generateCode()
        val smsResult = dispatchSms(phone, code)
        if (smsResult is SmsDispatchResult.Error) {
            return smsResult.status to smsResult.error
        }

        val sentResult = smsResult as SmsDispatchResult.Sent
        val hashed = hashOtp(phone, code)
        val expiresAt = now + ServerConfig.OTP_TTL_MS

        Database.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO otp_codes (phone, code, expires_at_ms, attempts, send_count, window_start_ms, last_sent_at_ms, locked_until_ms)
                VALUES (?, ?, ?, 0, ?, ?, ?, 0)
                ON CONFLICT (phone) DO UPDATE SET
                    code = EXCLUDED.code,
                    expires_at_ms = EXCLUDED.expires_at_ms,
                    attempts = 0,
                    send_count = EXCLUDED.send_count,
                    window_start_ms = EXCLUDED.window_start_ms,
                    last_sent_at_ms = EXCLUDED.last_sent_at_ms,
                    locked_until_ms = 0
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, phone)
                ps.setString(2, hashed)
                ps.setLong(3, expiresAt)
                ps.setInt(4, allowed.nextSendCount)
                ps.setLong(5, allowed.nextWindowStartMs)
                ps.setLong(6, now)
                ps.executeUpdate()
            }
        }

        return HttpStatusCode.OK to SendOtpResponse(
            sent = true,
            phone = phone,
            devOtp = sentResult.devOtp,
        )
    }

    fun verifyOtp(rawPhone: String, rawCode: String): Pair<HttpStatusCode, Any> {
        val phone = normalizePhone(rawPhone)
        val trimmedCode = rawCode.trim()
        if (!isValidNepalPhone(phone) || trimmedCode.length !in 4..10) {
            return HttpStatusCode.BadRequest to ErrorResponse("INVALID_INPUT", "Valid phone number and OTP code are required.")
        }
        val now = System.currentTimeMillis()

        return Database.transaction { conn ->
            val existing = loadOtpState(conn, phone, forUpdate = true)
            val matched = existing != null && verifyOtpHash(phone, trimmedCode, existing.codeHash)
            when (val eval = evaluateVerifyAttempt(existing, matched, now)) {
                is OtpVerifyEvaluation.Failed -> {
                    if (eval.nextAttempts != null) {
                        conn.prepareStatement(
                            """
                            UPDATE otp_codes
                            SET attempts = ?, locked_until_ms = COALESCE(?, locked_until_ms)
                            WHERE phone = ?
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setInt(1, eval.nextAttempts)
                            if (eval.nextLockedUntilMs == null) {
                                ps.setNull(2, java.sql.Types.BIGINT)
                            } else {
                                ps.setLong(2, eval.nextLockedUntilMs)
                            }
                            ps.setString(3, phone)
                            ps.executeUpdate()
                        }
                    }
                    eval.status to eval.error
                }

                OtpVerifyEvaluation.Verified -> {
                    conn.prepareStatement("DELETE FROM otp_codes WHERE phone = ?").use { ps ->
                        ps.setString(1, phone)
                        ps.executeUpdate()
                    }

                    var userId: String? = null
                    var role = "PATIENT"
                    var consentVersion: String? = null

                    conn.prepareStatement("SELECT id, role, consent_version FROM users WHERE phone = ?").use { ps ->
                        ps.setString(1, phone)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                userId = rs.getString("id")
                                role = rs.getString("role")
                                consentVersion = rs.getString("consent_version")
                            }
                        }
                    }

                    if (userId == null) {
                        val newId = UUID.randomUUID().toString()
                        conn.prepareStatement(
                            "INSERT INTO users (id, phone, role, created_at_ms) VALUES (?, ?, 'PATIENT', ?)",
                        ).use { ps ->
                            ps.setString(1, newId)
                            ps.setString(2, phone)
                            ps.setLong(3, now)
                            ps.executeUpdate()
                        }
                        userId = newId
                    }

                    HttpStatusCode.OK to AuthResponse(
                        accessToken = JwtService.generateAccessToken(userId!!, phone, role),
                        refreshToken = JwtService.generateRefreshToken(userId!!),
                        userId = userId!!,
                        phone = phone,
                        role = role,
                        consentVersion = consentVersion,
                    )
                }
            }
        }
    }

    private suspend fun dispatchSms(phone: String, code: String): SmsDispatchResult {
        val localPhone = toLocalTenDigitPhone(phone)
        val message = "Your M-Nirog verification code is $code. Valid for 5 minutes. Do not share this code."
        val pref = ServerConfig.smsProviderPreference
        val sparrowToken = ServerConfig.sparrowToken
        val aakashToken = ServerConfig.aakashToken

        return when {
            (pref == null || pref == "sparrow") && sparrowToken != null -> {
                sendViaSparrow(sparrowToken, ServerConfig.sparrowFrom, localPhone, message)
            }

            (pref == null || pref == "aakash") && aakashToken != null -> {
                sendViaAakash(aakashToken, localPhone, message)
            }

            ServerConfig.enableDevRoutes -> {
                println("[DEV OTP] $phone -> $code")
                SmsDispatchResult.Sent(devOtp = code)
            }

            else -> {
                SmsDispatchResult.Error(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(
                        "SMS_NOT_CONFIGURED",
                        "SMS OTP provider is not configured. Set SPARROW_SMS_TOKEN or AAKASH_SMS_AUTH_TOKEN.",
                    ),
                )
            }
        }
    }

    private suspend fun sendViaSparrow(
        token: String,
        from: String,
        toTenDigits: String,
        text: String,
    ): SmsDispatchResult {
        return runCatching {
            val resp = smsHttp.submitForm(
                url = ServerConfig.sparrowUrl,
                formParameters = Parameters.build {
                    append("token", token)
                    append("from", from)
                    append("to", toTenDigits)
                    append("text", text)
                },
            )
            val body = resp.bodyAsText()
            val obj = runCatching { smsJson.parseToJsonElement(body).jsonObject }.getOrNull()
            val code = obj?.get("response_code")?.jsonPrimitive?.intOrNull
            if (resp.status.isSuccess() && (code == null || code == 200)) {
                SmsDispatchResult.Sent(devOtp = null)
            } else {
                val detail = obj?.get("response")?.jsonPrimitive?.contentOrNull
                    ?: "Sparrow SMS error (${resp.status.value})"
                System.err.println("Sparrow SMS failed: $body")
                SmsDispatchResult.Error(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("SMS_DELIVERY_FAILED", detail),
                )
            }
        }.getOrElse { ex ->
            ex.printStackTrace()
            SmsDispatchResult.Error(
                HttpStatusCode.BadGateway,
                ErrorResponse("SMS_DELIVERY_FAILED", "Could not reach SMS gateway"),
            )
        }
    }

    private suspend fun sendViaAakash(
        authToken: String,
        toTenDigits: String,
        text: String,
    ): SmsDispatchResult {
        return runCatching {
            val resp = smsHttp.submitForm(
                url = ServerConfig.aakashUrl,
                formParameters = Parameters.build {
                    append("auth_token", authToken)
                    append("to", toTenDigits)
                    append("text", text)
                },
            )
            val body = resp.bodyAsText()
            val obj = runCatching { smsJson.parseToJsonElement(body).jsonObject }.getOrNull()
            val hasError = obj?.get("error")?.jsonPrimitive?.booleanOrNull == true
            if (resp.status.isSuccess() && !hasError) {
                SmsDispatchResult.Sent(devOtp = null)
            } else {
                val detail = obj?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "Aakash SMS error (${resp.status.value})"
                System.err.println("Aakash SMS failed: $body")
                SmsDispatchResult.Error(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("SMS_DELIVERY_FAILED", detail),
                )
            }
        }.getOrElse { ex ->
            ex.printStackTrace()
            SmsDispatchResult.Error(
                HttpStatusCode.BadGateway,
                ErrorResponse("SMS_DELIVERY_FAILED", "Could not reach SMS gateway"),
            )
        }
    }

    private fun loadOtpState(
        conn: java.sql.Connection,
        phone: String,
        forUpdate: Boolean = false,
    ): OtpRecordState? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return conn.prepareStatement(
            """
            SELECT code, expires_at_ms, attempts, send_count, window_start_ms, last_sent_at_ms, locked_until_ms
            FROM otp_codes
            WHERE phone = ?$lockClause
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, phone)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    OtpRecordState(
                        codeHash = rs.getString("code"),
                        expiresAtMs = rs.getLong("expires_at_ms"),
                        attempts = rs.getInt("attempts"),
                        sendCount = rs.getInt("send_count"),
                        windowStartMs = rs.getLong("window_start_ms"),
                        lastSentAtMs = rs.getLong("last_sent_at_ms"),
                        lockedUntilMs = rs.getLong("locked_until_ms"),
                    )
                }
            }
        }
    }
}
