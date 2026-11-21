package com.example.m_nirog

import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.AuthTokenStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

private class InMemoryTokenStore(
    var access: String?,
    var refresh: String?,
    var currentRole: String = "PATIENT",
) : AuthTokenStore {
    override fun accessToken(): String? = access
    override fun refreshToken(): String? = refresh
    override fun role(): String = currentRole
    override fun saveTokens(accessToken: String, refreshToken: String) {
        access = accessToken
        refresh = refreshToken
    }
    override fun saveRole(role: String) {
        currentRole = role
    }
    override fun clear() {
        access = null
        refresh = null
    }
}

class ApiClientAuthRefreshTest {
    @Test
    fun refreshesTokenOn401AndRetriesRequest() = runBlocking {
        val bookingCalls = AtomicInteger(0)
        val refreshCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/bookings") { ex ->
                bookingCalls.incrementAndGet()
                val auth = ex.requestHeaders.getFirst("Authorization")
                val (code, body) = if (auth == "Bearer fresh-access") {
                    200 to """[{"id":"b1"}]"""
                } else {
                    401 to """{"code":"UNAUTHORIZED","message":"Expired token"}"""
                }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.createContext("/auth/refresh") { ex ->
                refreshCalls.incrementAndGet()
                val reqBody = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                assertEquals("old-refresh", reqBody.getString("refreshToken"))
                val bytes = """{"accessToken":"fresh-access","refreshToken":"fresh-refresh","role":"PICKER"}""".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()

            val store = InMemoryTokenStore("expired-access", "old-refresh")
            val api = ApiClient(store, "http://127.0.0.1:${server.address.port}")
            val result = api.get("/bookings")

            assertEquals("""[{"id":"b1"}]""", result)
            assertEquals(2, bookingCalls.get())
            assertEquals(1, refreshCalls.get())
            assertEquals("fresh-access", store.access)
            assertEquals("fresh-refresh", store.refresh)
            assertEquals("PICKER", store.currentRole)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun clearsTokensWhenRefreshReturns401() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/bookings") { ex ->
                val bytes = """{"code":"UNAUTHORIZED","message":"Session expired"}""".toByteArray()
                ex.sendResponseHeaders(401, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.createContext("/auth/refresh") { ex ->
                val bytes = """{"code":"INVALID_REFRESH","message":"Invalid refresh token"}""".toByteArray()
                ex.sendResponseHeaders(401, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()

            val store = InMemoryTokenStore("expired", "bad-refresh")
            val api = ApiClient(store, "http://127.0.0.1:${server.address.port}")
            try {
                api.get("/bookings")
                fail("Expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertEquals("Session expired", e.message)
            }
            assertNull(store.access)
            assertNull(store.refresh)
        } finally {
            server.stop(0)
        }
    }
}
