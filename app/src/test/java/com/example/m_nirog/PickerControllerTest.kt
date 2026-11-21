package com.example.m_nirog

import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.AuthTokenStore
import com.example.m_nirog.models.PickerJobStatus
import com.example.m_nirog.ui.viewmodels.PickerController
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickerControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeController(
        scope: TestScope,
        baseUrl: String = "http://127.0.0.1:1",
        isStaff: Boolean = false,
        nowMs: () -> Long = { 1_000_000L },
    ) = PickerController(
        scope = scope,
        api = ApiClient(
            object : AuthTokenStore {
                override fun accessToken() = "tok"
                override fun refreshToken() = "ref"
                override fun role() = "PICKER"
                override fun saveTokens(accessToken: String, refreshToken: String) {}
                override fun saveRole(role: String) {}
                override fun clear() {}
            },
            baseUrl,
        ),
        isStaffSession = { isStaff },
        readUriBytes = { byteArrayOf(1, 2, 3) },
        encodeBase64 = { Base64.getEncoder().encodeToString(it) },
        nowMs = nowMs,
    )

    @Test
    fun offlineModeEnforcesNoShowWaitPhotoAndBarcodeValidation() = runTest {
        var now = 1_000_000L
        val controller = makeController(this, isStaff = false, nowMs = { now })
        controller.load()
        assertFalse(controller.state.value.liveMode)

        val pickupId = controller.state.value.pickups.first().id
        controller.markArrived(pickupId)
        assertEquals(PickerJobStatus.ARRIVED, controller.state.value.find(pickupId)?.status)

        controller.markNoShow(pickupId)
        assertTrue(controller.state.value.error?.contains("wait 15 minutes") == true)

        now += 16 * 60 * 1000L
        controller.markNoShow(pickupId)
        assertTrue(controller.state.value.error?.contains("photo proof") == true)

        controller.attachNoShowPhoto(pickupId, "file://door.jpg")
        controller.markNoShow(pickupId)
        assertEquals(PickerJobStatus.NO_SHOW, controller.state.value.find(pickupId)?.status)

        val secondId = controller.state.value.pickups[1].id
        controller.collectSample(secondId, "12")
        assertTrue(controller.state.value.error?.contains("valid sample") == true)

        controller.collectSample(secondId, "MNR-482190")
        assertEquals(PickerJobStatus.COLLECTED, controller.state.value.find(secondId)?.status)
        assertEquals("MNR-482190", controller.state.value.find(secondId)?.sampleCode)
    }

    @Test
    fun staffLiveModeLoadsAndResetsCleanly() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/picker/pickups") { ex ->
                val body = """[{"id":"pk-live","bookingId":"b-live","patientName":"Hari","patientPhone":"+9779800001111","packageName":"Basic Health","dateLabel":"Mon 23 Nov","slotLabel":"07:00-09:00","address":"Patan","city":"KTM","status":"ASSIGNED","arrivedAtMs":null,"sampleCode":null,"hasPhotoProof":false}]"""
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()

            val controller = makeController(this, "http://127.0.0.1:${server.address.port}", isStaff = true)
            controller.load()
            repeat(100) {
                advanceUntilIdle()
                if (controller.state.value.find("pk-live") != null) return@repeat
                Thread.sleep(10)
            }
            assertTrue(controller.state.value.liveMode)
            assertEquals("Hari", controller.state.value.find("pk-live")?.patientName)

            controller.reset()
            assertFalse(controller.state.value.liveMode)
            assertEquals(null, controller.state.value.find("pk-live"))
        } finally {
            server.stop(0)
        }
    }
}
