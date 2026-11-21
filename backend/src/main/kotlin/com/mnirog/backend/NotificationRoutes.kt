package com.mnirog.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

private val notifJson = Json { ignoreUnknownKeys = true }
private val fcmHttp = HttpClient(CIO)
private val notifScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Volatile
private var cachedFcmAccessToken: String? = null
@Volatile
private var cachedFcmTokenExpiryMs: Long = 0L

fun ensureNotificationsTable() {
    Database.useConnection { conn ->
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS notifications (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    booking_id TEXT,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    category TEXT NOT NULL,
                    is_read BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at_ms BIGINT NOT NULL
                );
                """.trimIndent(),
            )
        }
    }
}

private fun insertNotificationAndGetToken(
    userId: String,
    bookingId: String?,
    title: String,
    body: String,
    category: String,
): Pair<String, String?> {
    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    val fcmToken = Database.useConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO notifications (id, user_id, booking_id, title, body, category, is_read, created_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, userId)
            if (bookingId != null) ps.setString(3, bookingId) else ps.setNull(3, java.sql.Types.VARCHAR)
            ps.setString(4, title)
            ps.setString(5, body)
            ps.setString(6, category)
            ps.setLong(7, now)
            ps.executeUpdate()
        }

        conn.prepareStatement("SELECT fcm_token FROM users WHERE id = ?").use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("fcm_token")?.takeIf { it.isNotBlank() } else null
            }
        }
    }
    return id to fcmToken
}

fun notifyUser(
    userId: String,
    bookingId: String?,
    title: String,
    body: String,
    category: String = "BOOKING_UPDATE",
) {
    val (notificationId, fcmToken) = insertNotificationAndGetToken(userId, bookingId, title, body, category)
    if (!fcmToken.isNullOrBlank()) {
        notifScope.launch {
            runCatching { dispatchFcmPush(fcmToken, title, body, bookingId, category, notificationId) }
                .onFailure { it.printStackTrace() }
        }
    }
}

fun notifyPickupEvent(pickupId: String, status: String) {
    val info = Database.useConnection { conn ->
        conn.prepareStatement(
            "SELECT b.user_id, b.id FROM pickups pk JOIN bookings b ON b.id = pk.booking_id WHERE pk.id = ?",
        ).use { ps ->
            ps.setString(1, pickupId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) to rs.getString(2) else null
            }
        }
    }
    if (info != null) {
        notifyStatusTransition(info.first, info.second, status)
    }
}

fun notifyStatusTransition(
    userId: String,
    bookingId: String,
    status: String,
    urgent: Boolean = false,
    reason: String? = null,
) {
    val (title, body, cat) = when {
        urgent -> Triple(
            "URGENT: Critical lab result",
            "A critical lab value was found. Open M-Nirog to view urgent medical guidance immediately.",
            "URGENT_RESULT",
        )
        status == "PAID" -> Triple(
            "Booking confirmed",
            "Your payment is confirmed. A sample picker will be assigned shortly.",
            "PAYMENT",
        )
        status == "PICKER_ASSIGNED" -> Triple(
            "Picker assigned",
            "A phlebotomist has been assigned to your home sample pickup.",
            "PICKUP",
        )
        status == "SAMPLE_COLLECTED" -> Triple(
            "Sample collected",
            "Your sample has been collected and is on its way to the lab.",
            "PICKUP",
        )
        status == "AT_LAB" -> Triple(
            "Sample reached lab",
            "Your sample is now being processed at the laboratory.",
            "LAB",
        )
        status == "AWAITING_VERIFICATION" -> Triple(
            "Please verify your results",
            "Your draft lab values are ready. Compare with your PDF within 72 hours.",
            "REPORT",
        )
        status == "CORRECTION_REQUIRED" -> Triple(
            "Result correction requested",
            reason ?: "You flagged a value for correction. Our lab team is reviewing it.",
            "REPORT",
        )
        status == "DOCTOR_REVIEW" -> Triple(
            "Doctor reviewing results",
            "Your verified values are with our doctor for medical suggestions.",
            "REPORT",
        )
        status == "COMPLETED" -> Triple(
            "Final report ready",
            "Doctor suggestions and your signed lab report PDF are ready to view.",
            "REPORT",
        )
        status == "RECOLLECTION_REQUIRED" -> Triple(
            "New sample needed",
            reason ?: "The lab needs a fresh sample. Open your booking for free recollection details.",
            "RECOLLECTION",
        )
        status == "PATIENT_NO_SHOW" -> Triple(
            "Pickup missed",
            "Our picker waited 15 minutes at your address and uploaded doorway proof.",
            "NO_SHOW",
        )
        status == "CANCELLED" -> Triple(
            "Booking cancelled",
            reason ?: "Your booking has been cancelled.",
            "BOOKING_UPDATE",
        )
        status == "EXPIRED" -> Triple(
            "Booking hold expired",
            reason ?: "Your 15-minute payment hold expired and the time slot was released.",
            "BOOKING_UPDATE",
        )
        status == "EXPIRED" -> Triple(
            "Booking hold expired",
            reason ?: "Your 15-minute payment hold expired and the time slot was released.",
            "BOOKING_UPDATE",
        )
        else -> return
    }
    notifyUser(userId, bookingId, title, body, cat)
}

private suspend fun dispatchFcmPush(
    deviceToken: String,
    title: String,
    body: String,
    bookingId: String?,
    category: String,
    notificationId: String? = null,
): Pair<Boolean, String> {
    val credPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")?.trim()?.takeIf { it.isNotBlank() }
        ?: return false to "GOOGLE_APPLICATION_CREDENTIALS not set"
    val credFile = File(credPath)
    if (!credFile.exists()) return false to "Credentials file not found at $credPath"

    val saJson = notifJson.parseToJsonElement(credFile.readText()).jsonObject
    val projectId = saJson["project_id"]?.jsonPrimitive?.contentOrNull
        ?: return false to "Missing project_id in service account JSON"
    val clientEmail = saJson["client_email"]?.jsonPrimitive?.contentOrNull
        ?: return false to "Missing client_email in service account JSON"
    val privateKeyPem = saJson["private_key"]?.jsonPrimitive?.contentOrNull
        ?: return false to "Missing private_key in service account JSON"

    val accessToken = getOrFetchFcmAccessToken(clientEmail, privateKeyPem)
        ?: return false to "Failed to mint Google OAuth2 access token"

    val channelId = if (category in setOf("URGENT_RESULT", "RECOLLECTION", "NO_SHOW", "CORRECTION_REQUIRED")) {
        "mnirog_urgent"
    } else {
        "mnirog_updates"
    }

    val payload = buildJsonObject {
        put(
            "message",
            buildJsonObject {
                put("token", deviceToken)
                put(
                    "notification",
                    buildJsonObject {
                        put("title", title)
                        put("body", body)
                    },
                )
                put(
                    "android",
                    buildJsonObject {
                        put("priority", "HIGH")
                        put(
                            "notification",
                            buildJsonObject {
                                put("channel_id", channelId)
                            },
                        )
                    },
                )
                put(
                    "data",
                    buildJsonObject {
                        put("title", title)
                        put("body", body)
                        put("category", category)
                        if (bookingId != null) put("bookingId", bookingId)
                        if (notificationId != null) put("notificationId", notificationId)
                    },
                )
            },
        )
    }.toString()

    val resp = fcmHttp.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") {
        header("Authorization", "Bearer $accessToken")
        contentType(ContentType.Application.Json)
        setBody(payload)
    }
    val respBody = resp.bodyAsText()
    if (resp.status.isSuccess()) {
        return true to "Push sent"
    }
    System.err.println("FCM send failed (${resp.status.value}): $respBody")
    if (resp.status.value == 404 || respBody.contains("UNREGISTERED")) {
        Database.useConnection { conn ->
            conn.prepareStatement("UPDATE users SET fcm_token = NULL WHERE fcm_token = ?").use { ps ->
                ps.setString(1, deviceToken)
                ps.executeUpdate()
            }
        }
    }
    return false to "FCM ${resp.status.value}: ${respBody.take(180)}"
}

private suspend fun getOrFetchFcmAccessToken(clientEmail: String, privateKeyPem: String): String? {
    val now = System.currentTimeMillis()
    val existing = cachedFcmAccessToken
    if (!existing.isNullOrBlank() && now < cachedFcmTokenExpiryMs - 60_000L) {
        return existing
    }

    val cleanKey = privateKeyPem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\n", "")
        .replace("\n", "")
        .trim()
    val keyBytes = Base64.getDecoder().decode(cleanKey)
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))

    val nowSec = now / 1000L
    val b64 = Base64.getUrlEncoder().withoutPadding()
    val header = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val claims = b64.encodeToString(
        buildJsonObject {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            put("aud", "https://oauth2.googleapis.com/token")
            put("iat", nowSec)
            put("exp", nowSec + 3600L)
        }.toString().toByteArray(),
    )
    val signingInput = "$header.$claims"
    val sig = Signature.getInstance("SHA256withRSA").apply {
        initSign(privateKey)
        update(signingInput.toByteArray())
    }.sign()
    val jwtAssertion = "$signingInput.${b64.encodeToString(sig)}"

    val resp = fcmHttp.submitForm(
        url = "https://oauth2.googleapis.com/token",
        formParameters = Parameters.build {
            append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            append("assertion", jwtAssertion)
        },
    )
    if (!resp.status.isSuccess()) return null
    val obj = notifJson.parseToJsonElement(resp.bodyAsText()).jsonObject
    val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
    cachedFcmAccessToken = token
    cachedFcmTokenExpiryMs = now + 3000_000L
    return token
}

fun Route.registerNotificationRoutes() {
    post("/me/fcm-token") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val req = call.receive<FcmTokenRequest>()
        val token = req.token.trim()
        if (token.isNotBlank()) {
            Database.transaction { conn ->
                conn.prepareStatement("UPDATE users SET fcm_token = NULL WHERE fcm_token = ? AND id <> ?").use { ps ->
                    ps.setString(1, token)
                    ps.setString(2, userId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("UPDATE users SET fcm_token = ? WHERE id = ?").use { ps ->
                    ps.setString(1, token)
                    ps.setString(2, userId)
                    ps.executeUpdate()
                }
            }
        }
        call.respond(mapOf("saved" to true))
    }

    post("/me/fcm-token/clear") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        Database.useConnection { conn ->
            conn.prepareStatement("UPDATE users SET fcm_token = NULL WHERE id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
        call.respond(mapOf("cleared" to true))
    }

    post("/me/notifications/test") {
        if (!ServerConfig.enableDevRoutes) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("DEV_ROUTES_DISABLED", "Dev routes are disabled"))
            return@post
        }
        if (!ServerConfig.enableDevRoutes) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("DEV_ROUTES_DISABLED", "Dev routes are disabled"))
            return@post
        }
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val title = "M-Nirog test push"
        val body = "Firebase Cloud Messaging is connected for your account."
        val (notificationId, token) = insertNotificationAndGetToken(userId, null, title, body, "BOOKING_UPDATE")
        if (token.isNullOrBlank()) {
            call.respond(mapOf("status" to "no_token", "detail" to "Saved to inbox, but no FCM device token is registered yet"))
        } else {
            val (ok, detail) = dispatchFcmPush(token, title, body, null, "BOOKING_UPDATE", notificationId)
            call.respond(mapOf("status" to if (ok) "sent" else "failed", "detail" to detail))
        }
    }

    get("/me/notifications") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val items = Database.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT id, booking_id, title, body, category, is_read, created_at_ms
                FROM notifications
                WHERE user_id = ?
                ORDER BY created_at_ms DESC
                LIMIT 50
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                NotificationDto(
                                    id = rs.getString("id"),
                                    bookingId = rs.getString("booking_id"),
                                    title = rs.getString("title"),
                                    body = rs.getString("body"),
                                    category = rs.getString("category"),
                                    isRead = rs.getBoolean("is_read"),
                                    createdAtMs = rs.getLong("created_at_ms"),
                                ),
                            )
                        }
                    }
                }
            }
        }
        call.respond(
            NotificationsResponse(
                unreadCount = items.count { !it.isRead },
                items = items,
            ),
        )
    }

    post("/me/notifications/read-all") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        Database.useConnection { conn ->
            conn.prepareStatement("UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE").use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
        call.respond(mapOf("updated" to true))
    }

    post("/me/notifications/{id}/read") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val notifId = call.parameters["id"]!!
        Database.useConnection { conn ->
            conn.prepareStatement("UPDATE notifications SET is_read = TRUE WHERE id = ? AND user_id = ?").use { ps ->
                ps.setString(1, notifId)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
        call.respond(mapOf("updated" to true))
    }
}
