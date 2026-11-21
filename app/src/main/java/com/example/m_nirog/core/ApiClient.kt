package com.example.m_nirog.core

import com.example.m_nirog.data.AppError
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ApiClient(
    private val store: AuthTokenStore,
    private val baseUrl: String = ApiConfig.BASE_URL,
) {

    suspend fun get(path: String): String = request("GET", path, null)

    suspend fun post(path: String, body: JSONObject? = null): String =
        request("POST", path, body?.toString())

    suspend fun put(path: String, body: JSONObject): String =
        request("PUT", path, body.toString())

    suspend fun getBytes(path: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val tokenUsed = store.accessToken()
        val first = executeGetBytes(path, tokenUsed)
        if (first != null) return@withContext first

        val newToken = tryRefreshToken(tokenUsed)
            ?: throw IllegalStateException("Download failed (401)")
        executeGetBytes(path, newToken) ?: throw IllegalStateException("Download failed (401)")
    }

    private fun executeGetBytes(path: String, token: String?): Pair<String, ByteArray>? {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            val status = conn.responseCode
            if (status == 401 && path != "/auth/refresh" && !path.startsWith("/auth/otp")) {
                return null
            }
            if (status !in 200..299) {
                throw IllegalStateException("Download failed ($status)")
            }
            val mime = conn.contentType ?: "application/octet-stream"
            val bytes = conn.inputStream.use { it.readBytes() }
            mime to bytes
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun request(method: String, path: String, jsonBody: String?): String =
        withContext(Dispatchers.IO) {
            val tokenUsed = store.accessToken()
            val (status, raw) = executeRaw(method, path, jsonBody, tokenUsed)
            if (status == 401 && path != "/auth/refresh" && !path.startsWith("/auth/otp")) {
                val refreshed = tryRefreshToken(tokenUsed)
                if (refreshed != null) {
                    val (retryStatus, retryRaw) = executeRaw(method, path, jsonBody, refreshed)
                    return@withContext handleResponse(retryStatus, retryRaw)
                }
            }
            handleResponse(status, raw)
        }

    private fun executeRaw(
        method: String,
        path: String,
        jsonBody: String?,
        token: String?,
    ): Pair<Int, String> {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (jsonBody != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            status to raw
        } finally {
            conn.disconnect()
        }
    }

    private fun handleResponse(status: Int, raw: String): String {
        if (status !in 200..299) {
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            val code = parsed?.optString("code").orEmpty()
            val msg = parsed?.optString("message").orEmpty().ifBlank { "Server error ($status)" }
            if (code == "CANCEL_NOT_ALLOWED" || code == "NOT_FOUND") {
                throw AppError(code)
            }
            throw IllegalStateException(msg)
        }
        return raw
    }

    private fun tryRefreshToken(failedAccessToken: String?): String? {
        synchronized(refreshLock) {
            val currentAccess = store.accessToken()
            if (!currentAccess.isNullOrBlank() && currentAccess != failedAccessToken) {
                return currentAccess
            }
            val refresh = store.refreshToken() ?: return null
            val body = JSONObject().put("refreshToken", refresh).toString()
            val (status, raw) = executeRaw("POST", "/auth/refresh", body, null)
            if (status !in 200..299) {
                if (status == 401) store.clear()
                return null
            }
            return runCatching {
                val obj = JSONObject(raw)
                val access = obj.getString("accessToken")
                val nextRefresh = obj.getString("refreshToken")
                val role = obj.optString("role", store.role())
                store.saveTokens(access, nextRefresh)
                store.saveRole(role)
                access
            }.getOrNull()
        }
    }

    companion object {
        private val refreshLock = Any()
    }
}
