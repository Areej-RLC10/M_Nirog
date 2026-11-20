package com.mnirog.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val reportJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val devTimelineOrder = listOf(
    "PENDING_PAYMENT",
    "PAID",
    "PICKER_ASSIGNED",
    "SAMPLE_COLLECTED",
    "AT_LAB",
    "REPORT_UPLOADED",
    "AWAITING_VERIFICATION",
    "DOCTOR_REVIEW",
    "COMPLETED",
)

fun Route.registerPublicAuthRefreshRoute() {
    post("/auth/refresh") {
        val req = call.receive<RefreshTokenRequest>()
        val decoded = runCatching { JwtService.verifier.verify(req.refreshToken.trim()) }.getOrNull()
        val tokenType = decoded?.getClaim("type")?.asString()
        val userId = decoded?.subject
        if (decoded == null || tokenType != "refresh" || userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_REFRESH", "Session expired. Please sign in again."))
            return@post
        }

        val auth = Database.useConnection { conn ->
            conn.prepareStatement(
                "SELECT phone, role, consent_version FROM users WHERE id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else {
                        val phone = rs.getString("phone")
                        val role = rs.getString("role")
                        AuthResponse(
                            accessToken = JwtService.generateAccessToken(userId, phone, role),
                            refreshToken = JwtService.generateRefreshToken(userId),
                            userId = userId,
                            phone = phone,
                            role = role,
                            consentVersion = rs.getString("consent_version"),
                        )
                    }
                }
            }
        }
        if (auth == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_REFRESH", "User not found"))
        } else {
            call.respond(auth)
        }
    }
}

fun Route.registerReportAndBookingDevRoutes() {
    get("/bookings/{id}/report") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!
        val report = Database.transaction { conn ->
            val booking = conn.prepareStatement(
                """
                SELECT b.id, b.package_id, b.status, b.verification_deadline_ms, p.name AS package_name, s.date_iso
                FROM bookings b
                JOIN packages p ON p.id = b.package_id
                JOIN slots s ON s.id = b.slot_id
                WHERE b.id = ? AND b.user_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else arrayOf(
                        rs.getString("package_id"),
                        rs.getString("status"),
                        rs.getObject("verification_deadline_ms")?.let { (it as Number).toLong() },
                        rs.getString("package_name"),
                        rs.getString("date_iso"),
                    )
                }
            } ?: return@transaction null

            val pkgId = booking[0] as String
            val status = booking[1] as String
            val deadline = booking[2] as Long?
            val pkgName = booking[3] as String
            val dateLabel = formatReportDate(booking[4] as String)

            ensureLabReportRow(
                conn = conn,
                bookingId = bookingId,
                packageId = pkgId,
                urgent = false,
                withDoctor = status in setOf("DOCTOR_REVIEW", "COMPLETED"),
            )

            conn.prepareStatement(
                "SELECT summary_json, doctor_name, doctor_nmc, doctor_advice, is_urgent FROM lab_reports WHERE booking_id = ?",
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null else {
                        val values = reportJson.decodeFromString<List<LabValueDto>>(rs.getString("summary_json"))
                        ReportDetailDto(
                            bookingId = bookingId,
                            packageName = pkgName,
                            dateLabel = dateLabel,
                            values = values,
                            doctorName = rs.getString("doctor_name").takeIf { it.isNotBlank() },
                            doctorNmcNumber = rs.getString("doctor_nmc").takeIf { it.isNotBlank() },
                            doctorNote = rs.getString("doctor_advice").takeIf { it.isNotBlank() },
                            isUrgent = rs.getBoolean("is_urgent"),
                            verificationDeadlineMs = deadline,
                        )
                    }
                }
            }
        }
        if (report == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Report not found"))
        } else {
            call.respond(report)
        }
    }

    post("/bookings/{id}/report/verify") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!
        val now = System.currentTimeMillis()
        val ok = Database.transaction { conn ->
            val pkgId = conn.prepareStatement(
                "SELECT package_id FROM bookings WHERE id = ? AND user_id = ? FOR UPDATE",
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("package_id") else null }
            } ?: return@transaction false

            ensureLabReportRow(conn, bookingId, pkgId, urgent = false, withDoctor = true)
            conn.prepareStatement(
                "UPDATE lab_reports SET verified_at_ms = ? WHERE booking_id = ?",
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, bookingId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "UPDATE bookings SET status = 'DOCTOR_REVIEW', updated_at_ms = ? WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, bookingId)
                ps.executeUpdate()
            }
            true
        }
        val dto = if (ok) loadBookingDto(bookingId, userId) else null
        if (dto == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
        } else {
            notifyStatusTransition(userId, bookingId, dto.status)
            call.respond(dto)
        }
    }

    post("/bookings/{id}/report/flag") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!
        val req = call.receive<FlagValueRequest>()
        val note = req.note.trim()
        if (note.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("NOTE_REQUIRED", "Please describe what looks wrong."))
            return@post
        }
        val now = System.currentTimeMillis()
        val ok = Database.transaction { conn ->
            val pkgId = conn.prepareStatement(
                "SELECT package_id FROM bookings WHERE id = ? AND user_id = ? FOR UPDATE",
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("package_id") else null }
            } ?: return@transaction false

            ensureLabReportRow(conn, bookingId, pkgId, urgent = false, withDoctor = false)
            val currentValues = conn.prepareStatement(
                "SELECT summary_json FROM lab_reports WHERE booking_id = ?",
            ).use { ps ->
                ps.setString(1, bookingId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) reportJson.decodeFromString<List<LabValueDto>>(rs.getString("summary_json")) else emptyList()
                }
            }
            val updatedValues = currentValues.map { item ->
                if (item.id == req.valueId) item.copy(flaggedByPatient = true, patientNote = note) else item
            }
            conn.prepareStatement(
                """
                UPDATE lab_reports
                SET summary_json = ?, flagged_reason = ?, flagged_at_ms = ?
                WHERE booking_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, reportJson.encodeToString(updatedValues))
                ps.setString(2, note)
                ps.setLong(3, now)
                ps.setString(4, bookingId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "UPDATE bookings SET status = 'CORRECTION_REQUIRED', updated_at_ms = ? WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, bookingId)
                ps.executeUpdate()
            }
            true
        }
        val dto = if (ok) loadBookingDto(bookingId, userId) else null
        if (dto == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
        } else {
            call.respond(dto)
        }
    }

    post("/bookings/{id}/dev/advance") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!
        val currentStatus = Database.useConnection { conn ->
            conn.prepareStatement("SELECT status FROM bookings WHERE id = ? AND user_id = ?").use { ps ->
                ps.setString(1, bookingId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("status") else null }
            }
        }
        if (currentStatus == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
            return@post
        }
        val idx = devTimelineOrder.indexOf(currentStatus)
        val next = when {
            currentStatus == "CORRECTION_REQUIRED" -> "AWAITING_VERIFICATION"
            currentStatus == "RECOLLECTION_REQUIRED" -> "PICKER_ASSIGNED"
            idx >= 0 -> devTimelineOrder.getOrElse(idx + 1) { currentStatus }
            else -> currentStatus
        }
        val dto = applyDevBookingStatus(bookingId, userId, next, urgent = false, reason = null)
        call.respond(dto!!)
    }

    post("/bookings/{id}/dev/status") {
        val userId = call.principal<JWTPrincipal>()!!.subject!!
        val bookingId = call.parameters["id"]!!
        val req = call.receive<DevStatusRequest>()
        val dto = applyDevBookingStatus(bookingId, userId, req.status, req.urgent, req.reason)
        if (dto == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Booking not found"))
        } else {
            call.respond(dto)
        }
    }
}

fun loadUserBookings(userId: String): List<BookingDto> {
    return Database.useConnection { conn ->
        conn.prepareStatement(
            """
            SELECT b.*, p.name AS package_name, s.date_iso, s.label AS slot_label,
                   pu.full_name AS picker_name, pu.phone AS picker_phone,
                   COALESCE(lr.is_urgent, FALSE) AS is_urgent
            FROM bookings b
            JOIN packages p ON p.id = b.package_id
            JOIN slots s ON s.id = b.slot_id
            LEFT JOIN pickups pk ON pk.booking_id = b.id
            LEFT JOIN users pu ON pu.id = pk.picker_id
            LEFT JOIN lab_reports lr ON lr.booking_id = b.id
            WHERE b.user_id = ?
            ORDER BY b.created_at_ms DESC
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(mapBookingRow(rs))
                    }
                }
            }
        }
    }
}

fun loadBookingDto(bookingId: String, userId: String): BookingDto? {
    return Database.useConnection { conn ->
        conn.prepareStatement(
            """
            SELECT b.*, p.name AS package_name, s.date_iso, s.label AS slot_label,
                   pu.full_name AS picker_name, pu.phone AS picker_phone,
                   COALESCE(lr.is_urgent, FALSE) AS is_urgent
            FROM bookings b
            JOIN packages p ON p.id = b.package_id
            JOIN slots s ON s.id = b.slot_id
            LEFT JOIN pickups pk ON pk.booking_id = b.id
            LEFT JOIN users pu ON pu.id = pk.picker_id
            LEFT JOIN lab_reports lr ON lr.booking_id = b.id
            WHERE b.id = ? AND b.user_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, bookingId)
            ps.setString(2, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapBookingRow(rs) else null
            }
        }
    }
}

private fun mapBookingRow(rs: java.sql.ResultSet): BookingDto {
    val totalNpr = rs.getInt("total_npr")
    return BookingDto(
        id = rs.getString("id"),
        packageId = rs.getString("package_id"),
        packageName = rs.getString("package_name"),
        slotId = rs.getString("slot_id"),
        city = rs.getString("city"),
        dateLabel = formatReportDate(rs.getString("date_iso")),
        slotLabel = rs.getString("slot_label"),
        addressLine = rs.getString("address_line"),
        totalNpr = totalNpr,
        pricePaisa = totalNpr * 100,
        status = rs.getString("status"),
        holdExpiresAtMs = rs.getObject("hold_expires_at_ms")?.let { (it as Number).toLong() },
        updatedAtMs = rs.getLong("updated_at_ms"),
        pickerName = rs.getString("picker_name")?.takeIf { it.isNotBlank() },
        pickerPhone = rs.getString("picker_phone")?.takeIf { it.isNotBlank() },
        recollectionReason = rs.getString("recollection_reason")?.takeIf { it.isNotBlank() },
        recollectionCount = rs.getInt("recollection_count"),
        verificationDeadlineMs = rs.getObject("verification_deadline_ms")?.let { (it as Number).toLong() },
        isUrgent = rs.getBoolean("is_urgent"),
    )
}

private fun applyDevBookingStatus(
    bookingId: String,
    userId: String,
    targetStatus: String,
    urgent: Boolean,
    reason: String?,
): BookingDto? {
    val now = System.currentTimeMillis()
    val updated = Database.transaction { conn ->
        val row = conn.prepareStatement(
            "SELECT package_id, recollection_count FROM bookings WHERE id = ? AND user_id = ? FOR UPDATE",
        ).use { ps ->
            ps.setString(1, bookingId)
            ps.setString(2, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else rs.getString("package_id") to rs.getInt("recollection_count")
            }
        } ?: return@transaction false

        val pkgId = row.first
        var finalStatus = targetStatus
        var holdExpiresAt: Long? = null
        var verificationDeadline: Long? = null
        var recollectionReason: String? = null
        var recollectionCount = row.second

        when (targetStatus) {
            "PENDING_PAYMENT" -> {
                holdExpiresAt = now + 15 * 60_000L
            }
            "REPORT_UPLOADED", "AWAITING_VERIFICATION" -> {
                verificationDeadline = now + 72 * 3600_000L
                ensureLabReportRow(conn, bookingId, pkgId, urgent = false, withDoctor = false)
            }
            "DOCTOR_REVIEW", "COMPLETED" -> {
                ensureLabReportRow(conn, bookingId, pkgId, urgent = urgent, withDoctor = true)
            }
            "RECOLLECTION_REQUIRED" -> {
                recollectionCount += 1
                if (recollectionCount >= 2) {
                    finalStatus = "CANCELLED"
                    recollectionReason = reason?.takeIf { it.isNotBlank() }
                        ?: "Second sample could not be used — full refund started."
                    conn.prepareStatement(
                        "UPDATE payments SET status = 'REFUNDED' WHERE booking_id = ?",
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.executeUpdate()
                    }
                } else {
                    recollectionReason = reason?.takeIf { it.isNotBlank() }
                        ?: "Sample hemolyzed / insufficient volume"
                }
            }
        }

        if (finalStatus in setOf(
                "PICKER_ASSIGNED",
                "SAMPLE_COLLECTED",
                "AT_LAB",
                "REPORT_UPLOADED",
                "AWAITING_VERIFICATION",
                "DOCTOR_REVIEW",
                "COMPLETED",
            )
        ) {
            val pickerId = conn.prepareStatement(
                "SELECT id FROM users WHERE role = 'PICKER' ORDER BY created_at_ms ASC LIMIT 1",
            ).use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("id") else null }
            }
            if (pickerId != null) {
                conn.prepareStatement(
                    """
                    INSERT INTO pickups (id, booking_id, picker_id, status, updated_at_ms)
                    VALUES (?, ?, ?, 'ASSIGNED', ?)
                    ON CONFLICT (booking_id) DO NOTHING
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, "pk-${UUID.randomUUID().toString().take(8)}")
                    ps.setString(2, bookingId)
                    ps.setString(3, pickerId)
                    ps.setLong(4, now)
                    ps.executeUpdate()
                }
            }
        }

        conn.prepareStatement(
            """
            UPDATE bookings
            SET status = ?,
                hold_expires_at_ms = ?,
                verification_deadline_ms = COALESCE(?, verification_deadline_ms),
                recollection_reason = COALESCE(?, recollection_reason),
                recollection_count = ?,
                updated_at_ms = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, finalStatus)
            if (holdExpiresAt == null) ps.setNull(2, java.sql.Types.BIGINT) else ps.setLong(2, holdExpiresAt)
            if (verificationDeadline == null) ps.setNull(3, java.sql.Types.BIGINT) else ps.setLong(3, verificationDeadline)
            if (recollectionReason == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, recollectionReason)
            ps.setInt(5, recollectionCount)
            ps.setLong(6, now)
            ps.setString(7, bookingId)
            ps.executeUpdate()
        }
        true
    }
    val dto = if (updated) loadBookingDto(bookingId, userId) else null
    if (dto != null) {
        notifyStatusTransition(
            userId = userId,
            bookingId = bookingId,
            status = dto.status,
            urgent = urgent,
            reason = dto.recollectionReason,
        )
    }
    return dto
}

private fun ensureLabReportRow(
    conn: Connection,
    bookingId: String,
    packageId: String,
    urgent: Boolean,
    withDoctor: Boolean,
) {
    val existing = conn.prepareStatement("SELECT id, is_urgent, doctor_name FROM lab_reports WHERE booking_id = ?").use { ps ->
        ps.setString(1, bookingId)
        ps.executeQuery().use { rs ->
            if (rs.next()) Triple(rs.getString("id"), rs.getBoolean("is_urgent"), rs.getString("doctor_name")) else null
        }
    }
    val values = defaultValuesForPackage(packageId, urgent)
    val now = System.currentTimeMillis()
    val docName = if (withDoctor) "Suman Adhikari" else ""
    val docNmc = if (withDoctor) "14892" else ""
    val docAdvice = when {
        urgent -> "CRITICAL RESULT: Serum Potassium and Fasting Sugar are critically elevated. Seek immediate medical evaluation today."
        withDoctor -> "Fasting sugar is mildly elevated. Repeat HbA1c in 3 months and reduce refined sugar."
        else -> ""
    }

    if (existing == null) {
        conn.prepareStatement(
            """
            INSERT INTO lab_reports (id, booking_id, summary_json, doctor_name, doctor_nmc, doctor_advice, is_urgent, signed_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, UUID.randomUUID().toString())
            ps.setString(2, bookingId)
            ps.setString(3, reportJson.encodeToString(values))
            ps.setString(4, docName)
            ps.setString(5, docNmc)
            ps.setString(6, docAdvice)
            ps.setBoolean(7, urgent)
            if (withDoctor) ps.setLong(8, now) else ps.setNull(8, java.sql.Types.BIGINT)
            ps.executeUpdate()
        }
    } else if (urgent || (withDoctor && existing.third.isBlank())) {
        conn.prepareStatement(
            """
            UPDATE lab_reports
            SET summary_json = CASE WHEN ? THEN ? ELSE summary_json END,
                doctor_name = ?,
                doctor_nmc = ?,
                doctor_advice = ?,
                is_urgent = (is_urgent OR ?),
                signed_at_ms = COALESCE(signed_at_ms, ?)
            WHERE booking_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setBoolean(1, urgent)
            ps.setString(2, reportJson.encodeToString(values))
            ps.setString(3, "Suman Adhikari")
            ps.setString(4, "14892")
            ps.setString(5, docAdvice)
            ps.setBoolean(6, urgent)
            ps.setLong(7, now)
            ps.setString(8, bookingId)
            ps.executeUpdate()
        }
    }
}

private fun defaultValuesForPackage(packageId: String, urgent: Boolean): List<LabValueDto> {
    if (urgent) {
        return listOf(
            LabValueDto("1", "Serum Potassium", "6.4", "mmol/L", "3.5", "5.1", "CRITICAL"),
            LabValueDto("2", "Fasting Blood Sugar", "310", "mg/dL", "70", "99", "CRITICAL"),
            LabValueDto("3", "Hemoglobin", "13.4", "g/dL", "13.0", "17.0", "NORMAL"),
        )
    }
    return when (packageId) {
        "p-diabetes" -> listOf(
            LabValueDto("1", "Fasting Blood Sugar", "118", "mg/dL", "70", "99", "HIGH"),
            LabValueDto("2", "HbA1c", "6.3", "%", "4.0", "5.6", "HIGH"),
            LabValueDto("3", "Total Cholesterol", "192", "mg/dL", "0", "200", "NORMAL"),
        )
        "p-comprehensive" -> listOf(
            LabValueDto("1", "Hemoglobin", "13.8", "g/dL", "13.0", "17.0", "NORMAL"),
            LabValueDto("2", "Fasting Blood Sugar", "112", "mg/dL", "70", "99", "HIGH"),
            LabValueDto("3", "TSH", "2.4", "uIU/mL", "0.4", "4.2", "NORMAL"),
            LabValueDto("4", "Vitamin D", "22", "ng/mL", "30", "100", "LOW"),
        )
        else -> listOf(
            LabValueDto("1", "Hemoglobin", "13.8", "g/dL", "13.0", "17.0", "NORMAL"),
            LabValueDto("2", "Fasting Blood Sugar", "112", "mg/dL", "70", "99", "HIGH"),
            LabValueDto("3", "Total Cholesterol", "185", "mg/dL", "0", "200", "NORMAL"),
        )
    }
}

fun autoAdvanceExpiredVerifications() {
    val now = System.currentTimeMillis()
    Database.useConnection { conn ->
        conn.prepareStatement(
            """
            UPDATE bookings
            SET status = 'DOCTOR_REVIEW', updated_at_ms = ?
            WHERE status = 'AWAITING_VERIFICATION'
              AND verification_deadline_ms IS NOT NULL
              AND verification_deadline_ms < ?
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, now)
            ps.setLong(2, now)
            ps.executeUpdate()
        }
    }
}

private fun formatReportDate(dateIso: String): String {
    return runCatching {
        LocalDate.parse(dateIso).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }.getOrDefault(dateIso)
}
