package com.mnirog.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtpServiceAndConfigTest {
    @Test
    fun `enable dev routes defaults to false and only enables on true`() {
        assertFalse(ServerConfig.isDevRoutesEnabled(null))
        assertFalse(ServerConfig.isDevRoutesEnabled(""))
        assertFalse(ServerConfig.isDevRoutesEnabled("false"))
        assertFalse(ServerConfig.isDevRoutesEnabled("1"))
        assertTrue(ServerConfig.isDevRoutesEnabled("true"))
        assertTrue(ServerConfig.isDevRoutesEnabled(" TRUE "))
    }

    @Test
    fun `normalize and validate nepal phone numbers`() {
        val p1 = OtpService.normalizePhone("9841000001")
        assertEquals("+9779841000001", p1)
        assertTrue(OtpService.isValidNepalPhone(p1))
        assertEquals("9841000001", OtpService.toLocalTenDigitPhone(p1))

        assertEquals("+9779741234567", OtpService.normalizePhone("09741234567"))
        assertTrue(OtpService.isValidNepalPhone("+9779741234567"))

        assertFalse(OtpService.isValidNepalPhone(OtpService.normalizePhone("12345")))
        assertFalse(OtpService.isValidNepalPhone(OtpService.normalizePhone("8841000001")))
    }

    @Test
    fun `otp hashes match only for exact phone and code`() {
        val hash = OtpService.hashOtp("+9779841000001", "123456")
        assertNotEquals("123456", hash)
        assertTrue(OtpService.verifyOtpHash("+9779841000001", "123456", hash))
        assertFalse(OtpService.verifyOtpHash("+9779841000001", "654321", hash))
        assertFalse(OtpService.verifyOtpHash("+9779800000002", "123456", hash))
    }

    @Test
    fun `send eligibility enforces cooldown window limit and reset`() {
        val now = 1_700_000_000_000L

        val first = OtpService.evaluateSendEligibility(null, now)
        assertIs<OtpSendEligibility.Allowed>(first)
        assertEquals(1, first.nextSendCount)
        assertEquals(now, first.nextWindowStartMs)

        val stateAfterFirst = OtpRecordState(
            codeHash = "h",
            expiresAtMs = now + ServerConfig.OTP_TTL_MS,
            attempts = 0,
            sendCount = 1,
            windowStartMs = now,
            lastSentAtMs = now,
            lockedUntilMs = 0L,
        )
        val tooSoon = OtpService.evaluateSendEligibility(stateAfterFirst, now + 30_000L)
        assertIs<OtpSendEligibility.Blocked>(tooSoon)
        assertEquals(HttpStatusCode.TooManyRequests, tooSoon.status)
        assertEquals("OTP_COOLDOWN", tooSoon.error.code)

        val afterCooldown = OtpService.evaluateSendEligibility(stateAfterFirst, now + 61_000L)
        assertIs<OtpSendEligibility.Allowed>(afterCooldown)
        assertEquals(2, afterCooldown.nextSendCount)
        assertEquals(now, afterCooldown.nextWindowStartMs)

        val maxedWindow = stateAfterFirst.copy(
            sendCount = ServerConfig.OTP_MAX_SENDS_PER_WINDOW,
            lastSentAtMs = now + 100_000L,
        )
        val rateLimited = OtpService.evaluateSendEligibility(maxedWindow, now + 200_000L)
        assertIs<OtpSendEligibility.Blocked>(rateLimited)
        assertEquals("OTP_RATE_LIMITED", rateLimited.error.code)

        val nextWindow = OtpService.evaluateSendEligibility(
            maxedWindow,
            now + ServerConfig.OTP_SEND_WINDOW_MS + 1_000L,
        )
        assertIs<OtpSendEligibility.Allowed>(nextWindow)
        assertEquals(1, nextWindow.nextSendCount)
    }

    @Test
    fun `verify evaluation locks account after five failed attempts`() {
        val now = 1_700_000_000_000L
        var state = OtpRecordState(
            codeHash = OtpService.hashOtp("+9779841000001", "246810"),
            expiresAtMs = now + ServerConfig.OTP_TTL_MS,
            attempts = 0,
            sendCount = 1,
            windowStartMs = now,
            lastSentAtMs = now,
            lockedUntilMs = 0L,
        )

        repeat(4) { i ->
            val res = OtpService.evaluateVerifyAttempt(state, codeMatched = false, nowMs = now + 1_000L)
            assertIs<OtpVerifyEvaluation.Failed>(res)
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("INVALID_OTP", res.error.code)
            assertEquals(i + 1, res.nextAttempts)
            assertNull(res.nextLockedUntilMs)
            state = state.copy(attempts = res.nextAttempts!!)
        }

        val fifthFail = OtpService.evaluateVerifyAttempt(state, codeMatched = false, nowMs = now + 2_000L)
        assertIs<OtpVerifyEvaluation.Failed>(fifthFail)
        assertEquals(HttpStatusCode.TooManyRequests, fifthFail.status)
        assertEquals("OTP_LOCKED", fifthFail.error.code)
        assertEquals(5, fifthFail.nextAttempts)
        state = state.copy(attempts = fifthFail.nextAttempts!!, lockedUntilMs = fifthFail.nextLockedUntilMs!!)

        val lockedEvenWithValidCode = OtpService.evaluateVerifyAttempt(state, codeMatched = true, nowMs = now + 3_000L)
        assertIs<OtpVerifyEvaluation.Failed>(lockedEvenWithValidCode)
        assertEquals(HttpStatusCode.TooManyRequests, lockedEvenWithValidCode.status)
        assertEquals("OTP_LOCKED", lockedEvenWithValidCode.error.code)

        val afterExpiry = OtpService.evaluateVerifyAttempt(
            state.copy(lockedUntilMs = 0L),
            codeMatched = true,
            nowMs = state.expiresAtMs + 1L,
        )
        assertIs<OtpVerifyEvaluation.Failed>(afterExpiry)
        assertEquals(HttpStatusCode.Unauthorized, afterExpiry.status)
    }
}
