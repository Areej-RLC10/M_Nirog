package com.example.m_nirog

import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.AuthTokenStore
import com.example.m_nirog.data.FakeBookingRepository
import com.example.m_nirog.data.RemoteBookingRepository
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.City
import com.example.m_nirog.ui.viewmodels.BookingViewModel
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bookingCreationAndHoldExpiryTransitionsState() = runTest {
        val vm = BookingViewModel(FakeBookingRepository())
        vm.loadPackages()
        vm.loadDays()
        advanceUntilIdle()

        vm.selectPackage(vm.state.value.packages.first())
        vm.setCity(City.KTM)
        vm.setDay(vm.state.value.days.first())
        advanceUntilIdle()

        vm.setSlot(vm.state.value.slots.first())
        vm.setAddress("Naikap, Kathmandu")
        assertTrue(vm.state.value.canContinue)

        vm.createBooking()
        advanceUntilIdle()
        assertEquals(BookingStatus.PENDING_PAYMENT, vm.state.value.booking?.status)

        vm.expire()
        advanceUntilIdle()
        assertEquals(BookingStatus.EXPIRED, vm.state.value.booking?.status)

        vm.payWithKhalti()
        assertEquals("Live payment requires server connection.", vm.state.value.error)

        vm.reset()
        assertNull(vm.state.value.booking)
        assertTrue(vm.state.value.packages.isNotEmpty())
    }

    @Test
    fun khaltiCheckoutAndRedirectVerificationFlow() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var verifyStatus = "Completed"
        var bookingStatus = "PAID"
        try {
            server.createContext("/bookings") { ex ->
                val body = """{"id":"b99","packageName":"Basic Health","pricePaisa":450000,"dateLabel":"Mon 23 Nov","slotLabel":"07:00-09:00","addressLine":"Patan","status":"PENDING_PAYMENT","holdExpiresAtMs":9999999999999}"""
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.createContext("/bookings/b99/pay/khalti/initiate") { ex ->
                val body = """{"pidx":"pidx-abc","paymentUrl":"https://test-pay.khalti.com/?pidx=pidx-abc","returnUrl":"https://mnirog.local/khalti/return","amountPaisa":450000}"""
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.createContext("/bookings/b99/pay/khalti/verify") { ex ->
                val body = """{"paymentStatus":"$verifyStatus","transactionId":"tx-1","booking":{"id":"b99","packageName":"Basic Health","pricePaisa":450000,"dateLabel":"Mon 23 Nov","slotLabel":"07:00-09:00","addressLine":"Patan","status":"$bookingStatus","holdExpiresAtMs":null}}"""
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()

            val store = object : AuthTokenStore {
                override fun accessToken() = "tok"
                override fun refreshToken() = "ref"
                override fun role() = "PATIENT"
                override fun saveTokens(accessToken: String, refreshToken: String) {}
                override fun saveRole(role: String) {}
                override fun clear() {}
            }
            val repo = RemoteBookingRepository(ApiClient(store, "http://127.0.0.1:${server.address.port}"))
            val vm = BookingViewModel(repo)
            vm.openBooking("b99")
            repeat(100) {
                advanceUntilIdle()
                if (vm.state.value.booking != null) return@repeat
                Thread.sleep(10)
            }
            assertNotNull(vm.state.value.booking)

            vm.payWithKhalti()
            repeat(100) {
                advanceUntilIdle()
                if (vm.state.value.khaltiSession != null) return@repeat
                Thread.sleep(10)
            }
            assertEquals("pidx-abc", vm.state.value.khaltiSession?.pidx)

            vm.onKhaltiRedirect("pidx-abc", "Completed")
            repeat(100) {
                advanceUntilIdle()
                if (vm.state.value.booking?.status == BookingStatus.PAID) return@repeat
                Thread.sleep(10)
            }
            assertEquals(BookingStatus.PAID, vm.state.value.booking?.status)
            assertNull(vm.state.value.pendingKhaltiPidx)
            assertFalse(vm.state.value.paymentFailed)

            verifyStatus = "User canceled"
            bookingStatus = "PENDING_PAYMENT"
            vm.verifyKhaltiPayment("pidx-abc")
            repeat(100) {
                advanceUntilIdle()
                if (vm.state.value.paymentFailed) return@repeat
                Thread.sleep(10)
            }
            assertTrue(vm.state.value.paymentFailed)
            vm.consumePaymentFailed()
            assertFalse(vm.state.value.paymentFailed)
        } finally {
            server.stop(0)
        }
    }
}
