package com.mnirog.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8085
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    Database.init()
    ensureNotificationsTable()

    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", cause.message ?: "Invalid request"))
        }
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("SERVER_ERROR", cause.message ?: "Unexpected error"))
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JwtService.verifier)
            validate { credential ->
                if (!credential.payload.subject.isNullOrBlank()) JWTPrincipal(credential.payload) else null
            }
        }
    }

    launch {
        while (isActive) {
            runCatching { expireHeldBookings() }
            runCatching { autoAdvanceExpiredVerifications() }
            delay(30_000L)
        }
    }

    routing {
        get("/health") {
            val ok = Database.useConnection { conn ->
                conn.createStatement().use { it.executeQuery("SELECT 1").next() }
            }
            call.respond(mapOf("status" to if (ok) "ok" else "error"))
        }

        post("/auth/otp/send") {
            val req = call.receive<SendOtpRequest>()
            val phone = normalizePhone(req.phone)
            val code = Random.nextInt(100000, 999999).toString()
            val expiresAt = System.currentTimeMillis() + 5 * 60_000L

            Database.useConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO otp_codes (phone, code, expires_at_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT (phone) DO UPDATE SET code = EXCLUDED.code, expires_at_ms = EXCLUDED.expires_at_ms
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, phone)
                    ps.setString(2, code)
                    ps.setLong(3, expiresAt)
                    ps.executeUpdate()
                }
            }
            println("[DEV OTP] $phone -> $code")
            call.respond(SendOtpResponse(sent = true, phone = phone, devOtp = code))
        }

        post("/auth/otp/verify") {
            val req = call.receive<VerifyOtpRequest>()
            val phone = normalizePhone(req.phone)
            val now = System.currentTimeMillis()

            val auth = Database.transaction { conn ->
                val valid = conn.prepareStatement(
                    "SELECT code, expires_at_ms FROM otp_codes WHERE phone = ?",
                ).use { ps ->
                    ps.setString(1, phone)
                    ps.executeQuery().use { rs ->
                        rs.next() && rs.getString("code") == req.code.trim() && rs.getLong("expires_at_ms") >= now
                    }
                }
                if (!valid) return@transaction null

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

                AuthResponse(
                    accessToken = JwtService.generateAccessToken(userId!!, phone, role),
                    refreshToken = JwtService.generateRefreshToken(userId!!),
                    userId = userId!!,
                    phone = phone,
                    role = role,
                    consentVersion = consentVersion,
                )
            }

            if (auth == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_OTP", "Invalid or expired OTP code"))
            } else {
                call.respond(auth)
            }
        }

        registerPublicAuthRefreshRoute()

        get("/packages") {
            val list = Database.useConnection { conn ->
                conn.prepareStatement("SELECT * FROM packages ORDER BY price_npr ASC").use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val priceNpr = rs.getInt("price_npr")
                                add(
                                    PackageDto(
                                        id = rs.getString("id"),
                                        name = rs.getString("name"),
                                        priceNpr = priceNpr,
                                        pricePaisa = priceNpr * 100,
                                        fastingHours = rs.getInt("fasting_hours"),
                                        description = rs.getString("description"),
                                        includedTests = rs.getString("included_tests").split("|").filter { it.isNotBlank() },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            call.respond(list)
        }

        get("/slots/days") {
            val today = LocalDate.now(ZoneId.of("Asia/Kathmandu"))
            val days = (1..5).map { offset ->
                val iso = today.plusDays(offset.toLong()).toString()
                SlotDayDto(dateIso = iso, label = formatDateLabel(iso))
            }
            call.respond(days)
        }

        get("/slots") {
            val city = (call.request.queryParameters["city"] ?: "KTM").uppercase(Locale.US)
            val dateIso = call.request.queryParameters["date"]
            if (city !in setOf("KTM", "PKR")) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("UNSUPPORTED_CITY", "Only Kathmandu (KTM) and Pokhara (PKR) are supported"))
                return@get
            }
            val list = Database.useConnection { conn ->
                val sql = if (dateIso.isNullOrBlank()) {
                    "SELECT id, city, date_iso, label, (capacity - booked_count) AS remaining FROM slots WHERE city = ? ORDER BY date_iso, label"
                } else {
                    "SELECT id, city, date_iso, label, (capacity - booked_count) AS remaining FROM slots WHERE city = ? AND date_iso = ? ORDER BY label"
                }
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, city)
                    if (!dateIso.isNullOrBlank()) ps.setString(2, dateIso)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    SlotDto(
                                        id = rs.getString("id"),
                                        city = rs.getString("city"),
                                        dateIso = rs.getString("date_iso"),
                                        label = rs.getString("label"),
                                        remaining = rs.getInt("remaining").coerceAtLeast(0),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            call.respond(list)
        }

        authenticate("auth-jwt") {
            post("/auth/consent") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<ConsentRequest>()
                val now = System.currentTimeMillis()
                Database.useConnection { conn ->
                    conn.prepareStatement(
                        "UPDATE users SET consent_version = ?, consented_at_ms = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setString(1, req.version)
                        ps.setLong(2, now)
                        ps.setString(3, userId)
                        ps.executeUpdate()
                    }
                }
                call.respond(mapOf("saved" to true, "version" to req.version))
            }

            get("/me/profile") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val profile = loadProfile(userId)
                if (profile == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "User not found"))
                } else {
                    call.respond(profile)
                }
            }

            put("/me/profile") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<UpdateProfileRequest>()
                val name = req.fullName.trim()
                if (name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Full name is required."))
                    return@put
                }
                Database.useConnection { conn ->
                    conn.prepareStatement(
                        "UPDATE users SET full_name = ?, dob = ?, gender = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setString(1, name)
                        ps.setString(2, req.dob.trim())
                        ps.setString(3, req.gender.trim())
                        ps.setString(4, userId)
                        ps.executeUpdate()
                    }
                }
                call.respond(loadProfile(userId)!!)
            }

            post("/me/addresses") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<CreateAddressRequest>()
                val rawCity = req.city.trim()
                val cityCode = when {
                    rawCity.equals("Kathmandu", ignoreCase = true) || rawCity.equals("KTM", ignoreCase = true) -> "KTM"
                    rawCity.equals("Pokhara", ignoreCase = true) || rawCity.equals("PKR", ignoreCase = true) -> "PKR"
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("UNSUPPORTED_CITY", "Service is not available in $rawCity yet."),
                        )
                        return@post
                    }
                }
                val street = req.street.trim()
                if (street.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Please enter an address."))
                    return@post
                }
                val addressId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                Database.useConnection { conn ->
                    conn.prepareStatement(
                        "INSERT INTO addresses (id, user_id, city, street, landmark, created_at_ms) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, addressId)
                        ps.setString(2, userId)
                        ps.setString(3, cityCode)
                        ps.setString(4, street)
                        ps.setString(5, req.landmark.trim())
                        ps.setLong(6, now)
                        ps.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AddressDto(addressId, cityCode, street, req.landmark.trim()))
            }

            get("/me/records") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val list = Database.useConnection { conn ->
                    conn.prepareStatement(
                        "SELECT id, title, record_type, record_date, file_key, mime_type, created_at_ms FROM health_records WHERE user_id = ? ORDER BY created_at_ms DESC",
                    ).use { ps ->
                        ps.setString(1, userId)
                        ps.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    val id = rs.getString("id")
                                    val fileKey = rs.getString("file_key")
                                    add(
                                        HealthRecordDto(
                                            id = id,
                                            title = rs.getString("title"),
                                            recordType = rs.getString("record_type"),
                                            recordDate = rs.getString("record_date"),
                                            fileUrl = if (fileKey.isNotBlank()) "/me/records/$id/file" else null,
                                            mimeType = rs.getString("mime_type"),
                                            createdAtMs = rs.getLong("created_at_ms"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                call.respond(list)
            }

            post("/me/records") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<CreateHealthRecordRequest>()
                val title = req.title.trim()
                val date = req.recordDate.trim()
                if (title.isBlank() || date.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Title and date are required."))
                    return@post
                }
                val recordId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                var fileKey = ""
                if (req.fileBase64.isNotBlank()) {
                    val bytes = Base64.getDecoder().decode(req.fileBase64)
                    val dir = File("uploads/records/$userId").apply { mkdirs() }
                    val ext = when {
                        req.mimeType.contains("pdf", ignoreCase = true) -> "pdf"
                        req.mimeType.contains("png", ignoreCase = true) -> "png"
                        req.mimeType.contains("image", ignoreCase = true) -> "jpg"
                        else -> "bin"
                    }
                    val target = File(dir, "$recordId.$ext")
                    target.writeBytes(bytes)
                    fileKey = target.path
                }
                Database.useConnection { conn ->
                    conn.prepareStatement(
                        """
                        INSERT INTO health_records (id, user_id, title, record_type, record_date, file_key, mime_type, created_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, recordId)
                        ps.setString(2, userId)
                        ps.setString(3, title)
                        ps.setString(4, req.recordType.trim())
                        ps.setString(5, date)
                        ps.setString(6, fileKey)
                        ps.setString(7, req.mimeType.ifBlank { "application/octet-stream" })
                        ps.setLong(8, now)
                        ps.executeUpdate()
                    }
                }
                call.respond(
                    HttpStatusCode.Created,
                    HealthRecordDto(
                        id = recordId,
                        title = title,
                        recordType = req.recordType.trim(),
                        recordDate = date,
                        fileUrl = if (fileKey.isNotBlank()) "/me/records/$recordId/file" else null,
                        mimeType = req.mimeType,
                        createdAtMs = now,
                    ),
                )
            }

            get("/me/records/{id}/file") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val recordId = call.parameters["id"]!!
                val row = Database.useConnection { conn ->
                    conn.prepareStatement(
                        "SELECT title, file_key, mime_type FROM health_records WHERE id = ? AND user_id = ?",
                    ).use { ps ->
                        ps.setString(1, recordId)
                        ps.setString(2, userId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else Triple(
                                rs.getString("title"),
                                rs.getString("file_key"),
                                rs.getString("mime_type"),
                            )
                        }
                    }
                }
                if (row == null || row.second.isBlank()) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "File not found"))
                    return@get
                }
                val file = File(row.second)
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "File missing on disk"))
                    return@get
                }
                val contentType = runCatching { ContentType.parse(row.third) }.getOrDefault(ContentType.Application.OctetStream)
                call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"${file.name}\"")
                call.respondBytes(file.readBytes(), contentType)
            }

            registerReportAndBookingDevRoutes()
            registerKhaltiPaymentRoutes()
            registerNotificationRoutes()

            get("/bookings") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                call.respond(loadUserBookings(userId))
            }

            get("/bookings/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val bookingId = call.parameters["id"]!!
                val found = loadBookingDto(bookingId, userId)
                if (found == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
                } else {
                    call.respond(found)
                }
            }

            post("/bookings") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<CreateBookingRequest>()
                val city = req.city.uppercase(Locale.US)
                if (city !in setOf("KTM", "PKR")) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("UNSUPPORTED_CITY", "Only KTM and PKR are supported"))
                    return@post
                }
                val now = System.currentTimeMillis()
                val holdExpiresAt = now + 15 * 60_000L
                val bookingId = UUID.randomUUID().toString()

                val created = Database.transaction { conn ->
                    val pkg = conn.prepareStatement("SELECT name, price_npr FROM packages WHERE id = ?").use { ps ->
                        ps.setString(1, req.packageId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getString("name") to rs.getInt("price_npr")
                        }
                    } ?: throw IllegalArgumentException("Package not found")

                    val slot = conn.prepareStatement(
                        "SELECT date_iso, label, capacity, booked_count FROM slots WHERE id = ? FOR UPDATE",
                    ).use { ps ->
                        ps.setString(1, req.slotId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else Triple(
                                rs.getString("date_iso"),
                                rs.getString("label"),
                                rs.getInt("capacity") - rs.getInt("booked_count"),
                            )
                        }
                    } ?: throw IllegalArgumentException("Slot not found")

                    if (slot.third <= 0) {
                        return@transaction null
                    }

                    conn.prepareStatement("UPDATE slots SET booked_count = booked_count + 1 WHERE id = ?").use { ps ->
                        ps.setString(1, req.slotId)
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(
                        """
                        INSERT INTO bookings (id, user_id, package_id, slot_id, city, address_line, total_npr, status, hold_expires_at_ms, created_at_ms, updated_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.setString(2, userId)
                        ps.setString(3, req.packageId)
                        ps.setString(4, req.slotId)
                        ps.setString(5, city)
                        ps.setString(6, req.addressLine.trim())
                        ps.setInt(7, pkg.second)
                        ps.setLong(8, holdExpiresAt)
                        ps.setLong(9, now)
                        ps.setLong(10, now)
                        ps.executeUpdate()
                    }

                    BookingDto(
                        id = bookingId,
                        packageId = req.packageId,
                        packageName = pkg.first,
                        slotId = req.slotId,
                        city = city,
                        dateLabel = formatDateLabel(slot.first),
                        slotLabel = slot.second,
                        addressLine = req.addressLine.trim(),
                        totalNpr = pkg.second,
                        pricePaisa = pkg.second * 100,
                        status = "PENDING_PAYMENT",
                        holdExpiresAtMs = holdExpiresAt,
                        updatedAtMs = now,
                    )
                }

                if (created == null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("SLOT_FULL", "Selected slot is no longer available"))
                } else {
                    call.respond(HttpStatusCode.Created, created)
                }
            }

            post("/bookings/{id}/pay/dev") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val bookingId = call.parameters["id"]!!
                val now = System.currentTimeMillis()
                val updated = Database.transaction { conn ->
                    val amountPaisa = conn.prepareStatement(
                        "SELECT total_npr FROM bookings WHERE id = ? AND user_id = ? AND status = 'PENDING_PAYMENT' FOR UPDATE",
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.setString(2, userId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getInt("total_npr") * 100
                        }
                    } ?: return@transaction 0

                    conn.prepareStatement(
                        """
                        UPDATE bookings
                        SET status = 'PAID', hold_expires_at_ms = NULL, updated_at_ms = ?
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, bookingId)
                        ps.executeUpdate()
                    }

                    conn.prepareStatement(
                        """
                        INSERT INTO payments (id, booking_id, provider, pidx, amount_paisa, status, created_at_ms, verified_at_ms)
                        VALUES (?, ?, 'DEV_KHALTI', ?, ?, 'COMPLETED', ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, UUID.randomUUID().toString())
                        ps.setString(2, bookingId)
                        ps.setString(3, "dev_${bookingId.take(8)}")
                        ps.setInt(4, amountPaisa)
                        ps.setLong(5, now)
                        ps.setLong(6, now)
                        ps.executeUpdate()
                    }
                    1
                }
                if (updated == 0) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("PAYMENT_CONFLICT", "Booking is not awaiting payment"))
                } else {
                    notifyStatusTransition(userId, bookingId, "PAID")
                    call.respond(mapOf("status" to "PAID"))
                }
            }

            post("/bookings/{id}/expire") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val bookingId = call.parameters["id"]!!
                val now = System.currentTimeMillis()
                val found = Database.transaction { conn ->
                    val row = conn.prepareStatement(
                        "SELECT status, slot_id FROM bookings WHERE id = ? AND user_id = ? FOR UPDATE",
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.setString(2, userId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getString("status") to rs.getString("slot_id")
                        }
                    } ?: return@transaction false

                    if (row.first == "PENDING_PAYMENT") {
                        conn.prepareStatement(
                            "UPDATE bookings SET status = 'EXPIRED', hold_expires_at_ms = NULL, updated_at_ms = ? WHERE id = ?",
                        ).use { ps ->
                            ps.setLong(1, now)
                            ps.setString(2, bookingId)
                            ps.executeUpdate()
                        }
                        conn.prepareStatement(
                            "UPDATE slots SET booked_count = GREATEST(booked_count - 1, 0) WHERE id = ?",
                        ).use { ps ->
                            ps.setString(1, row.second)
                            ps.executeUpdate()
                        }
                    }
                    true
                }

                if (!found) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
                } else {
                    val dto = loadBookingDto(bookingId, userId)
                    if (dto == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
                    } else {
                        call.respond(dto)
                    }
                }
            }

            post("/bookings/{id}/cancel") {
                val userId = call.principal<JWTPrincipal>()!!.subject!!
                val bookingId = call.parameters["id"]!!
                val now = System.currentTimeMillis()
                val result = Database.transaction { conn ->
                    val row = conn.prepareStatement(
                        "SELECT status, slot_id FROM bookings WHERE id = ? AND user_id = ? FOR UPDATE",
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.setString(2, userId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getString("status") to rs.getString("slot_id")
                        }
                    } ?: return@transaction "NOT_FOUND"

                    if (row.first !in setOf("PENDING_PAYMENT", "PAID")) {
                        return@transaction "CANCEL_NOT_ALLOWED"
                    }

                    conn.prepareStatement(
                        "UPDATE bookings SET status = 'CANCELLED', hold_expires_at_ms = NULL, updated_at_ms = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, bookingId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        "UPDATE slots SET booked_count = GREATEST(booked_count - 1, 0) WHERE id = ?",
                    ).use { ps ->
                        ps.setString(1, row.second)
                        ps.executeUpdate()
                    }
                    "OK"
                }

                when (result) {
                    "NOT_FOUND" -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
                    "CANCEL_NOT_ALLOWED" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("CANCEL_NOT_ALLOWED", "Cannot cancel after picker assignment"))
                    else -> {
                        notifyStatusTransition(userId, bookingId, "CANCELLED")
                        call.respond(mapOf("status" to "CANCELLED"))
                    }
                }
            }

            get("/picker/pickups") {
                val principal = call.principal<JWTPrincipal>()!!
                val role = principal.getClaim("role", String::class)
                if (role !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                    return@get
                }
                val pickerId = principal.subject!!
                call.respond(loadPickerPickups(pickerId))
            }

            post("/picker/assign-paid") {
                val principal = call.principal<JWTPrincipal>()!!
                val role = principal.getClaim("role", String::class)
                if (role !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                    return@post
                }
                val pickerId = principal.subject!!
                val now = System.currentTimeMillis()
                val paidIds = Database.transaction { conn ->
                    val paidIds = conn.prepareStatement(
                        """
                        SELECT b.id FROM bookings b
                        LEFT JOIN pickups pk ON pk.booking_id = b.id
                        WHERE b.status = 'PAID' AND pk.id IS NULL
                        """.trimIndent(),
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.getString(1)) }
                        }
                    }
                    paidIds.forEach { bId ->
                        conn.prepareStatement(
                            """
                            INSERT INTO pickups (id, booking_id, picker_id, status, updated_at_ms)
                            VALUES (?, ?, ?, 'ASSIGNED', ?)
                            ON CONFLICT (booking_id) DO NOTHING
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(1, "pk-${bId.take(8)}")
                            ps.setString(2, bId)
                            ps.setString(3, pickerId)
                            ps.setLong(4, now)
                            ps.executeUpdate()
                        }
                        conn.prepareStatement(
                            "UPDATE bookings SET status = 'PICKER_ASSIGNED', updated_at_ms = ? WHERE id = ?",
                        ).use { ps ->
                            ps.setLong(1, now)
                            ps.setString(2, bId)
                            ps.executeUpdate()
                        }
                    }
                    paidIds
                }
                paidIds.forEach { bId -> notifyPickupEvent("pk-" + bId.take(8), "PICKER_ASSIGNED") }
                call.respond(loadPickerPickups(pickerId))
            }

            post("/picker/pickups/{id}/arrive") {
                val principal = call.principal<JWTPrincipal>()!!
                if (principal.getClaim("role", String::class) !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                    return@post
                }
                val pickerId = principal.subject!!
                val pickupId = call.parameters["id"]!!
                val now = System.currentTimeMillis()
                val ok = Database.transaction { conn ->
                    conn.prepareStatement(
                        """
                        UPDATE pickups
                        SET status = 'ARRIVED', arrived_at_ms = COALESCE(arrived_at_ms, ?), updated_at_ms = ?
                        WHERE id = ? AND picker_id = ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setLong(2, now)
                        ps.setString(3, pickupId)
                        ps.setString(4, pickerId)
                        ps.executeUpdate() > 0
                    }
                }
                if (!ok) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Pickup not found"))
                } else {
                    val pickupInfo = Database.useConnection { conn ->
                        conn.prepareStatement(
                            "SELECT b.user_id, b.id FROM pickups pk JOIN bookings b ON b.id = pk.booking_id WHERE pk.id = ?",
                        ).use { ps ->
                            ps.setString(1, pickupId)
                            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
                        }
                    }
                    if (pickupInfo != null) {
                        notifyUser(
                            pickupInfo.first,
                            pickupInfo.second,
                            "Picker has arrived",
                            "Your phlebotomist has arrived at your doorway.",
                            "PICKUP",
                        )
                    }
                    call.respond(loadPickerPickupById(pickupId, pickerId)!!)
                }
            }

            post("/picker/pickups/{id}/dev-complete-wait") {
                val principal = call.principal<JWTPrincipal>()!!
                if (principal.getClaim("role", String::class) !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                    return@post
                }
                val pickerId = principal.subject!!
                val pickupId = call.parameters["id"]!!
                val now = System.currentTimeMillis()
                val past = now - 16 * 60_000L
                val ok = Database.transaction { conn ->
                    conn.prepareStatement(
                        """
                        UPDATE pickups
                        SET status = 'ARRIVED', arrived_at_ms = ?, updated_at_ms = ?
                        WHERE id = ? AND picker_id = ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setLong(1, past)
                        ps.setLong(2, now)
                        ps.setString(3, pickupId)
                        ps.setString(4, pickerId)
                        ps.executeUpdate() > 0
                    }
                }
                if (!ok) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Pickup not found"))
                } else {
                    call.respond(loadPickerPickupById(pickupId, pickerId)!!)
                }
            }

            post("/picker/pickups/{id}/collect") {
                val principal = call.principal<JWTPrincipal>()!!
                if (principal.getClaim("role", String::class) !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                    return@post
                }
                val pickerId = principal.subject!!
                val pickupId = call.parameters["id"]!!
                val req = call.receive<CollectSampleRequest>()
                val code = req.sampleCode.trim()
                if (code.length < 4) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BARCODE", "Scan or enter a valid sample tube barcode."))
                    return@post
                }
                val now = System.currentTimeMillis()
                val bookingId = Database.transaction { conn ->
                    val bId = conn.prepareStatement(
                        """
                        UPDATE pickups
                        SET status = 'COLLECTED', sample_barcode = ?, updated_at_ms = ?
                        WHERE id = ? AND picker_id = ?
                        RETURNING booking_id
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, code)
                        ps.setLong(2, now)
                        ps.setString(3, pickupId)
                        ps.setString(4, pickerId)
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    } ?: return@transaction null

                    conn.prepareStatement(
                        "UPDATE bookings SET status = 'SAMPLE_COLLECTED', updated_at_ms = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, bId)
                        ps.executeUpdate()
                    }
                    bId
                }
                if (bookingId == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Pickup not found"))
                } else {
                    notifyPickupEvent(pickupId, "SAMPLE_COLLECTED")
                    call.respond(loadPickerPickupById(pickupId, pickerId)!!)
                }
            }

            get("/picker/pickups/{id}/photo") {
                val principal = call.principal<JWTPrincipal>()!!
                if (principal.getClaim("role", String::class) !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                } else {
                    val pickerId = principal.subject!!
                    val pickupId = call.parameters["id"]!!
                    val photoPath = Database.useConnection { conn ->
                        conn.prepareStatement(
                            "SELECT doorway_photo_key FROM pickups WHERE id = ? AND picker_id = ?",
                        ).use { ps ->
                            ps.setString(1, pickupId)
                            ps.setString(2, pickerId)
                            ps.executeQuery().use { rs ->
                                if (rs.next()) rs.getString("doorway_photo_key") else null
                            }
                        }
                    }
                    val file = photoPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                    if (file == null || !file.exists()) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "No doorway photo proof found"))
                    } else {
                        call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
                    }
                }
            }

            post("/picker/pickups/{id}/no-show") {
                val principal = call.principal<JWTPrincipal>()!!
                if (principal.getClaim("role", String::class) !in setOf("PICKER", "ADMIN")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Staff picker access required"))
                    return@post
                }
                val pickerId = principal.subject!!
                val pickupId = call.parameters["id"]!!
                val req = call.receive<NoShowRequest>()
                val now = System.currentTimeMillis()
                if (req.photoBase64.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("PHOTO_REQUIRED", "Attach doorway photo proof first."))
                    return@post
                }

                val outcome = Database.transaction { conn ->
                    val row = conn.prepareStatement(
                        "SELECT booking_id, arrived_at_ms FROM pickups WHERE id = ? AND picker_id = ? FOR UPDATE",
                    ).use { ps ->
                        ps.setString(1, pickupId)
                        ps.setString(2, pickerId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getString("booking_id") to rs.getObject("arrived_at_ms")?.let { (it as Number).toLong() }
                        }
                    } ?: return@transaction "NOT_FOUND"

                    val arrivedAt = row.second
                    if (arrivedAt == null || (now - arrivedAt) < 15 * 60_000L) {
                        return@transaction "WAIT_REQUIRED"
                    }

                    val dir = File("uploads/no_show").apply { mkdirs() }
                    val photoFile = File(dir, "$pickupId.jpg")
                    photoFile.writeBytes(Base64.getDecoder().decode(req.photoBase64))

                    conn.prepareStatement(
                        """
                        UPDATE pickups
                        SET status = 'NO_SHOW', doorway_photo_key = ?, updated_at_ms = ?
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, photoFile.path)
                        ps.setLong(2, now)
                        ps.setString(3, pickupId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        "UPDATE bookings SET status = 'PATIENT_NO_SHOW', updated_at_ms = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setLong(1, now)
                        ps.setString(2, row.first)
                        ps.executeUpdate()
                    }
                    "OK"
                }

                when (outcome) {
                    "NOT_FOUND" -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Pickup not found"))
                    "WAIT_REQUIRED" -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("WAIT_REQUIRED", "You must wait 15 minutes before marking no-show."))
                    else -> {
                        notifyPickupEvent(pickupId, "PATIENT_NO_SHOW")
                        call.respond(loadPickerPickupById(pickupId, pickerId)!!)
                    }
                }
            }
        }
    }
}

private fun loadPickerPickups(pickerId: String): List<PickerPickupDto> {
    return Database.useConnection { conn ->
        conn.prepareStatement(
            """
            SELECT pk.id, pk.booking_id, pk.status, pk.sample_barcode, pk.arrived_at_ms, pk.doorway_photo_key,
                   u.full_name AS patient_name, u.phone AS patient_phone,
                   p.name AS package_name, s.date_iso, s.label AS slot_label,
                   b.address_line, b.city
            FROM pickups pk
            JOIN bookings b ON b.id = pk.booking_id
            JOIN users u ON u.id = b.user_id
            JOIN packages p ON p.id = b.package_id
            JOIN slots s ON s.id = b.slot_id
            WHERE pk.picker_id = ?
            ORDER BY pk.updated_at_ms DESC
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, pickerId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val name = rs.getString("patient_name").ifBlank { rs.getString("patient_phone") }
                        add(
                            PickerPickupDto(
                                id = rs.getString("id"),
                                bookingId = rs.getString("booking_id"),
                                patientName = name,
                                patientPhone = rs.getString("patient_phone"),
                                packageName = rs.getString("package_name"),
                                dateLabel = formatDateLabel(rs.getString("date_iso")),
                                slotLabel = rs.getString("slot_label"),
                                address = rs.getString("address_line"),
                                city = rs.getString("city"),
                                status = rs.getString("status"),
                                sampleCode = rs.getString("sample_barcode"),
                                arrivedAtMs = rs.getObject("arrived_at_ms")?.let { (it as Number).toLong() },
                                hasPhotoProof = !rs.getString("doorway_photo_key").isNullOrBlank(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun loadPickerPickupById(pickupId: String, pickerId: String): PickerPickupDto? =
    loadPickerPickups(pickerId).firstOrNull { it.id == pickupId }

private fun loadProfile(userId: String): ProfileDto? {
    return Database.useConnection { conn ->
        val user = conn.prepareStatement(
            "SELECT full_name, dob, gender FROM users WHERE id = ?",
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else Triple(
                    rs.getString("full_name"),
                    rs.getString("dob"),
                    rs.getString("gender"),
                )
            }
        } ?: return@useConnection null

        val addresses = conn.prepareStatement(
            "SELECT id, city, street, landmark FROM addresses WHERE user_id = ? ORDER BY created_at_ms ASC",
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AddressDto(
                                id = rs.getString("id"),
                                city = rs.getString("city"),
                                street = rs.getString("street"),
                                landmark = rs.getString("landmark"),
                            ),
                        )
                    }
                }
            }
        }

        ProfileDto(
            fullName = user.first,
            dob = user.second,
            gender = user.third,
            addresses = addresses,
        )
    }
}

private fun expireHeldBookings() {
    val now = System.currentTimeMillis()
    Database.transaction { conn ->
        val expiredSlots = mutableListOf<String>()
        conn.prepareStatement(
            """
            UPDATE bookings
            SET status = 'EXPIRED', hold_expires_at_ms = NULL, updated_at_ms = ?
            WHERE status = 'PENDING_PAYMENT' AND hold_expires_at_ms IS NOT NULL AND hold_expires_at_ms < ?
            RETURNING slot_id
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, now)
            ps.setLong(2, now)
            ps.executeQuery().use { rs ->
                while (rs.next()) expiredSlots.add(rs.getString("slot_id"))
            }
        }
        if (expiredSlots.isNotEmpty()) {
            conn.prepareStatement(
                "UPDATE slots SET booked_count = GREATEST(booked_count - 1, 0) WHERE id = ?",
            ).use { ps ->
                expiredSlots.forEach { slotId ->
                    ps.setString(1, slotId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }
}

private fun normalizePhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.startsWith("977") -> "+$digits"
        digits.length == 10 && digits.startsWith("9") -> "+977$digits"
        else -> "+$digits"
    }
}

private fun formatDateLabel(dateIso: String): String {
    return runCatching {
        LocalDate.parse(dateIso).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }.getOrDefault(dateIso)
}
