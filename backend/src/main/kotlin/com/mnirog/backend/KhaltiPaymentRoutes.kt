package com.mnirog.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

private val khaltiJson = Json { ignoreUnknownKeys = true }
private val khaltiHttp = HttpClient(CIO)

private fun khaltiSecretKey(): String? = System.getenv("KHALTI_SECRET_KEY")?.trim()?.takeIf { it.isNotBlank() }

private fun khaltiBaseUrl(): String =
    (System.getenv("KHALTI_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: "https://dev.khalti.com/api/v2")

private fun khaltiWebsiteUrl(): String =
    (System.getenv("KHALTI_WEBSITE_URL")?.trim()?.takeIf { it.isNotBlank() }
        ?: "https://mnirog.example.com")

private fun khaltiReturnUrl(): String =
    (System.getenv("KHALTI_RETURN_URL")?.trim()?.takeIf { it.isNotBlank() }
        ?: "https://mnirog.example.com/khalti/callback")

private fun toTenDigitNepalPhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length == 10 -> digits
        digits.startsWith("977") && digits.length >= 13 -> digits.takeLast(10)
        else -> "9800000001"
    }
}

private fun extractKhaltiError(rawBody: String, statusCode: Int): String {
    val parsed = runCatching { khaltiJson.parseToJsonElement(rawBody).jsonObject }.getOrNull()
    if (parsed != null) {
        parsed["detail"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        parsed["message"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        val firstFieldMsg = parsed.entries.firstNotNullOfOrNull { (_, v) ->
            when (v) {
                is JsonPrimitive -> v.contentOrNull
                else -> runCatching { v.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull()
            }
        }
        if (!firstFieldMsg.isNullOrBlank()) return firstFieldMsg
    }
    return "Khalti API error ($statusCode)"
}

fun Route.registerKhaltiPaymentRoutes() {
    post("/bookings/{id}/pay/khalti/initiate") {
        val secretKey = khaltiSecretKey()
        if (secretKey == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "KHALTI_NOT_CONFIGURED",
                    "Set KHALTI_SECRET_KEY in your backend environment from test-admin.khalti.com to use Khalti Sandbox.",
                ),
            )
            return@post
        }

        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!

        data class BookingPayRow(
            val status: String,
            val amountPaisa: Int,
            val packageName: String,
            val customerName: String,
            val customerPhone: String,
        )

        val row = Database.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT b.status, b.total_npr, p.name AS package_name,
                       u.full_name, u.phone
                FROM bookings b
                JOIN packages p ON p.id = b.package_id
                JOIN users u ON u.id = b.user_id
                WHERE b.id = ? AND b.user_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else BookingPayRow(
                        status = rs.getString("status"),
                        amountPaisa = rs.getInt("total_npr") * 100,
                        packageName = rs.getString("package_name"),
                        customerName = rs.getString("full_name").ifBlank { "M-Nirog Patient" },
                        customerPhone = toTenDigitNepalPhone(rs.getString("phone")),
                    )
                }
            }
        }

        if (row == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
            return@post
        }
        if (row.status != "PENDING_PAYMENT") {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("PAYMENT_CONFLICT", "Booking is not awaiting payment"))
            return@post
        }

        val returnUrl = khaltiReturnUrl()
        val payload = buildJsonObject {
            put("return_url", returnUrl)
            put("website_url", khaltiWebsiteUrl())
            put("amount", row.amountPaisa)
            put("purchase_order_id", bookingId)
            put("purchase_order_name", row.packageName)
            put(
                "customer_info",
                buildJsonObject {
                    put("name", row.customerName)
                    put("email", "patient@mnirog.local")
                    put("phone", row.customerPhone)
                },
            )
        }.toString()

        val httpResp = khaltiHttp.post("${khaltiBaseUrl()}/epayment/initiate/") {
            header("Authorization", "Key $secretKey")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val rawResp = httpResp.bodyAsText()
        if (!httpResp.status.isSuccess()) {
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse("KHALTI_INITIATE_FAILED", extractKhaltiError(rawResp, httpResp.status.value)),
            )
            return@post
        }

        val obj: JsonObject = runCatching { khaltiJson.parseToJsonElement(rawResp).jsonObject }.getOrNull()
            ?: run {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("KHALTI_INITIATE_FAILED", "Invalid response from Khalti"))
                return@post
            }
        val pidx = obj["pidx"]?.jsonPrimitive?.contentOrNull
        val paymentUrl = obj["payment_url"]?.jsonPrimitive?.contentOrNull
        val expiresAt = obj["expires_at"]?.jsonPrimitive?.contentOrNull
        if (pidx.isNullOrBlank() || paymentUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("KHALTI_INITIATE_FAILED", "Missing pidx or payment_url from Khalti"))
            return@post
        }

        val now = System.currentTimeMillis()
        Database.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO payments (id, booking_id, provider, pidx, amount_paisa, status, created_at_ms, verified_at_ms)
                VALUES (?, ?, 'KHALTI_SANDBOX', ?, ?, 'INITIATED', ?, NULL)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, bookingId)
                ps.setString(3, pidx)
                ps.setInt(4, row.amountPaisa)
                ps.setLong(5, now)
                ps.executeUpdate()
            }
        }

        call.respond(
            KhaltiInitiateResponse(
                pidx = pidx,
                paymentUrl = paymentUrl,
                returnUrl = returnUrl,
                amountPaisa = row.amountPaisa,
                expiresAt = expiresAt,
            ),
        )
    }

    post("/bookings/{id}/pay/khalti/verify") {
        val secretKey = khaltiSecretKey()
        if (secretKey == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "KHALTI_NOT_CONFIGURED",
                    "Set KHALTI_SECRET_KEY in your backend environment from test-admin.khalti.com to use Khalti Sandbox.",
                ),
            )
            return@post
        }

        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!
        val req = runCatching { call.receive<KhaltiVerifyRequest>() }.getOrElse { KhaltiVerifyRequest() }

        val existingBooking = loadBookingDto(bookingId, userId)
        if (existingBooking == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
            return@post
        }
        if (existingBooking.status == "PAID") {
            call.respond(KhaltiVerifyResponse(paymentStatus = "Completed", transactionId = null, booking = existingBooking))
            return@post
        }
        if (existingBooking.status != "PENDING_PAYMENT") {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("PAYMENT_CONFLICT", "Booking is no longer awaiting payment (${existingBooking.status})"))
            return@post
        }

        val pidx = req.pidx?.trim()?.takeIf { it.isNotBlank() } ?: Database.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT pidx FROM payments
                WHERE booking_id = ? AND provider = 'KHALTI_SANDBOX' AND pidx IS NOT NULL
                ORDER BY created_at_ms DESC LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("pidx") else null }
            }
        }

        if (pidx.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PIDX", "No Khalti payment found for this booking"))
            return@post
        }

        val lookupPayload = buildJsonObject { put("pidx", pidx) }.toString()
        val httpResp = khaltiHttp.post("${khaltiBaseUrl()}/epayment/lookup/") {
            header("Authorization", "Key $secretKey")
            contentType(ContentType.Application.Json)
            setBody(lookupPayload)
        }
        val rawResp = httpResp.bodyAsText()
        if (!httpResp.status.isSuccess()) {
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse("KHALTI_VERIFY_FAILED", extractKhaltiError(rawResp, httpResp.status.value)),
            )
            return@post
        }

        val obj = runCatching { khaltiJson.parseToJsonElement(rawResp).jsonObject }.getOrNull()
            ?: run {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("KHALTI_VERIFY_FAILED", "Invalid response from Khalti lookup"))
                return@post
            }

        val paymentStatus = obj["status"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
        val totalAmount = obj["total_amount"]?.jsonPrimitive?.intOrNull ?: 0
        val transactionId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull
        val now = System.currentTimeMillis()

        Database.transaction { conn ->
            val expectedPaisa = conn.prepareStatement(
                "SELECT status, total_npr FROM bookings WHERE id = ? AND user_id = ? FOR UPDATE",
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString("status") to (rs.getInt("total_npr") * 100)
                }
            } ?: return@transaction

            if (paymentStatus.equals("Completed", ignoreCase = true) && totalAmount >= expectedPaisa.second) {
                if (expectedPaisa.first == "PENDING_PAYMENT") {
                    conn.prepareStatement(
                        "UPDATE bookings SET status = 'PAID', hold_expires_at_ms = NULL, updated_at_ms = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, bookingId)
                        ps.executeUpdate()
                    }
                }
                conn.prepareStatement(
                    "UPDATE payments SET status = 'COMPLETED', verified_at_ms = ? WHERE booking_id = ? AND pidx = ?",
                ).use { ps ->
                    ps.setLong(1, now)
                    ps.setString(2, bookingId)
                    ps.setString(3, pidx)
                    ps.executeUpdate()
                }
            } else if (paymentStatus in setOf("Expired", "User canceled", "Failed", "Refunded")) {
                conn.prepareStatement(
                    "UPDATE payments SET status = ? WHERE booking_id = ? AND pidx = ?",
                ).use { ps ->
                    ps.setString(1, paymentStatus.uppercase().replace(" ", "_"))
                    ps.setString(2, bookingId)
                    ps.setString(3, pidx)
                    ps.executeUpdate()
                }
            }
        }

        val updatedBooking = loadBookingDto(bookingId, userId) ?: existingBooking
        if (existingBooking.status != "PAID" && updatedBooking.status == "PAID") {
            notifyStatusTransition(userId, bookingId, "PAID")
        }
        call.respond(
            KhaltiVerifyResponse(
                paymentStatus = paymentStatus,
                transactionId = transactionId,
                booking = updatedBooking,
            ),
        )
    }
}
