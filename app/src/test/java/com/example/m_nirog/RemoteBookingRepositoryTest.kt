package com.example.m_nirog

import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.AuthTokenStore
import com.example.m_nirog.data.RemoteBookingRepository
import com.example.m_nirog.models.BookingStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StaticTokenStore : AuthTokenStore {
    override fun accessToken(): String = "tok"
    override fun refreshToken(): String = "ref"
    override fun role(): String = "PATIENT"
    override fun saveTokens(accessToken: String, refreshToken: String) {}
    override fun saveRole(role: String) {}
    override fun clear() {}
}

class RemoteBookingRepositoryTest {
    private fun respondJson(server: HttpServer, path: String, json: String) {
        server.createContext(path) { ex ->
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun parsesPackagesBookingExpireAndKhaltiResponses() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            respondJson(
                server,
                "/packages",
                """[{"id":"p-basic","code":"BASIC","name":"Basic Health","description":"Essential","pricePaisa":450000,"isActive":true,"includedTests":["CBC","FBS"]}]""",
            )
            respondJson(
                server,
                "/bookings/b1/expire",
                """{"id":"b1","packageName":"Basic Health","pricePaisa":450000,"dateLabel":"Mon 23 Nov","slotLabel":"07:00-09:00","addressLine":"Naikap","status":"EXPIRED","holdExpiresAtMs":null,"pickerName":null,"pickerPhone":null,"recollectionReason":null,"recollectionCount":0,"isUrgent":false}""",
            )
            respondJson(
                server,
                "/bookings/b1/pay/khalti/initiate",
                """{"pidx":"pidx-123","paymentUrl":"https://test-pay.khalti.com/?pidx=pidx-123","returnUrl":"https://mnirog.local/khalti/return","amountPaisa":450000}""",
            )
            respondJson(
                server,
                "/bookings/b1/pay/khalti/verify",
                """{"paymentStatus":"Completed","transactionId":"tx-99","booking":{"id":"b1","packageName":"Basic Health","pricePaisa":450000,"dateLabel":"Mon 23 Nov","slotLabel":"07:00-09:00","addressLine":"Naikap","status":"PAID","holdExpiresAtMs":null,"pickerName":"Staff Picker","pickerPhone":"+9779800000002","recollectionReason":null,"recollectionCount":0,"isUrgent":true}}""",
            )
            server.start()

            val repo = RemoteBookingRepository(ApiClient(StaticTokenStore(), "http://127.0.0.1:${server.address.port}"))
            val pkgs = repo.getPackages()
            assertEquals(1, pkgs.size)
            assertEquals(listOf("CBC", "FBS"), pkgs.first().includedTests)

            val expired = repo.expireBooking("b1")
            assertEquals(BookingStatus.EXPIRED, expired.status)

            val session = repo.initiateKhalti("b1")
            assertEquals("pidx-123", session.pidx)
            assertEquals(450000, session.amountPaisa)

            val verified = repo.verifyKhalti("b1", session.pidx)
            assertEquals("Completed", verified.paymentStatus)
            assertEquals("tx-99", verified.transactionId)
            assertEquals(BookingStatus.PAID, verified.booking.status)
            assertEquals("Staff Picker", verified.booking.pickerName)
            assertTrue(verified.booking.isUrgent)
        } finally {
            server.stop(0)
        }
    }
}
