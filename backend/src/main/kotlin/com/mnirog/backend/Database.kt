package com.mnirog.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object Database {
    private lateinit var dataSource: HikariDataSource

    fun init() {
        val dbUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/mnirog"
        val dbUser = System.getenv("PGUSER") ?: System.getProperty("user.name")
        val dbPass = System.getenv("PGPASSWORD") ?: ""

        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPass
            maximumPoolSize = 10
            isAutoCommit = true
        }
        dataSource = HikariDataSource(config)
        createSchema()
        seedData()
    }

    fun <T> useConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T {
        return dataSource.connection.use { conn ->
            val previous = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = previous
            }
        }
    }

    private fun createSchema() {
        useConnection { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT PRIMARY KEY,
                        phone TEXT UNIQUE NOT NULL,
                        full_name TEXT NOT NULL DEFAULT '',
                        dob TEXT NOT NULL DEFAULT '',
                        gender TEXT NOT NULL DEFAULT '',
                        role TEXT NOT NULL DEFAULT 'PATIENT',
                        consent_version TEXT,
                        consented_at_ms BIGINT,
                        fcm_token TEXT,
                        created_at_ms BIGINT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS otp_codes (
                        phone TEXT PRIMARY KEY,
                        code TEXT NOT NULL,
                        expires_at_ms BIGINT NOT NULL,
                        attempts INT NOT NULL DEFAULT 0,
                        send_count INT NOT NULL DEFAULT 0,
                        window_start_ms BIGINT NOT NULL DEFAULT 0,
                        last_sent_at_ms BIGINT NOT NULL DEFAULT 0,
                        locked_until_ms BIGINT NOT NULL DEFAULT 0
                    );
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS send_count INT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS window_start_ms BIGINT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS last_sent_at_ms BIGINT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS locked_until_ms BIGINT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS send_count INT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS window_start_ms BIGINT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS last_sent_at_ms BIGINT NOT NULL DEFAULT 0;
                    ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS locked_until_ms BIGINT NOT NULL DEFAULT 0;
                    CREATE TABLE IF NOT EXISTS packages (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        price_npr INT NOT NULL,
                        fasting_hours INT NOT NULL,
                        description TEXT NOT NULL,
                        included_tests TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS slots (
                        id TEXT PRIMARY KEY,
                        city TEXT NOT NULL,
                        date_iso TEXT NOT NULL,
                        label TEXT NOT NULL,
                        capacity INT NOT NULL,
                        booked_count INT NOT NULL DEFAULT 0,
                        UNIQUE (city, date_iso, label)
                    );
                    CREATE TABLE IF NOT EXISTS addresses (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        city TEXT NOT NULL,
                        street TEXT NOT NULL,
                        landmark TEXT NOT NULL DEFAULT '',
                        created_at_ms BIGINT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS bookings (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id),
                        package_id TEXT NOT NULL REFERENCES packages(id),
                        slot_id TEXT NOT NULL REFERENCES slots(id),
                        city TEXT NOT NULL,
                        address_line TEXT NOT NULL,
                        total_npr INT NOT NULL,
                        status TEXT NOT NULL,
                        hold_expires_at_ms BIGINT,
                        khalti_pidx TEXT,
                        recollection_reason TEXT,
                        recollection_count INT NOT NULL DEFAULT 0,
                        verification_deadline_ms BIGINT,
                        created_at_ms BIGINT NOT NULL,
                        updated_at_ms BIGINT NOT NULL
                    );
                    ALTER TABLE bookings ADD COLUMN IF NOT EXISTS recollection_reason TEXT;
                    ALTER TABLE bookings ADD COLUMN IF NOT EXISTS recollection_count INT NOT NULL DEFAULT 0;
                    ALTER TABLE bookings ADD COLUMN IF NOT EXISTS verification_deadline_ms BIGINT;
                    CREATE TABLE IF NOT EXISTS pickups (
                        id TEXT PRIMARY KEY,
                        booking_id TEXT UNIQUE NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
                        picker_id TEXT NOT NULL REFERENCES users(id),
                        status TEXT NOT NULL,
                        arrived_at_ms BIGINT,
                        sample_barcode TEXT,
                        doorway_photo_key TEXT,
                        updated_at_ms BIGINT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS lab_reports (
                        id TEXT PRIMARY KEY,
                        booking_id TEXT UNIQUE NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
                        pdf_key TEXT,
                        summary_json TEXT NOT NULL,
                        doctor_name TEXT NOT NULL DEFAULT '',
                        doctor_nmc TEXT NOT NULL DEFAULT '',
                        doctor_advice TEXT NOT NULL DEFAULT '',
                        is_urgent BOOLEAN NOT NULL DEFAULT FALSE,
                        flagged_reason TEXT,
                        flagged_at_ms BIGINT,
                        verified_at_ms BIGINT,
                        signed_at_ms BIGINT
                    );
                    ALTER TABLE lab_reports ADD COLUMN IF NOT EXISTS doctor_advice TEXT NOT NULL DEFAULT '';
                    ALTER TABLE lab_reports ADD COLUMN IF NOT EXISTS flagged_reason TEXT;
                    ALTER TABLE lab_reports ADD COLUMN IF NOT EXISTS flagged_at_ms BIGINT;
                    CREATE TABLE IF NOT EXISTS payments (
                        id TEXT PRIMARY KEY,
                        booking_id TEXT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
                        provider TEXT NOT NULL,
                        pidx TEXT,
                        amount_paisa INT NOT NULL,
                        status TEXT NOT NULL,
                        created_at_ms BIGINT NOT NULL,
                        verified_at_ms BIGINT
                    );
                    CREATE TABLE IF NOT EXISTS health_records (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        title TEXT NOT NULL,
                        record_type TEXT NOT NULL,
                        record_date TEXT NOT NULL,
                        file_key TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        created_at_ms BIGINT NOT NULL
                    );
                    """.trimIndent(),
                )
            }
        }
    }

    private fun seedData() {
        val now = System.currentTimeMillis()
        useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO packages (id, name, price_npr, fasting_hours, description, included_tests)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                val packages = listOf(
                    listOf("p-basic", "Basic Health", "4500", "8", "Essential blood & metabolic screen", "CBC|Fasting Blood Sugar|Lipid Profile|Liver Function (LFT)|Kidney Function (KFT)"),
                    listOf("p-comprehensive", "Full Body Checkup", "8500", "12", "Complete preventive panel + vitamins & thyroid", "CBC|HbA1c|Lipid Profile|Thyroid (TSH, T3, T4)|Vitamin D & B12|LFT & KFT"),
                    listOf("p-diabetes", "Diabetes & Heart Care", "6200", "10", "Targeted glucose, lipid & cardiac risk panel", "Fasting Blood Sugar|HbA1c|Lipid Profile|hs-CRP|Kidney Function (KFT)"),
                )
                packages.forEach { p ->
                    ps.setString(1, p[0])
                    ps.setString(2, p[1])
                    ps.setInt(3, p[2].toInt())
                    ps.setInt(4, p[3].toInt())
                    ps.setString(5, p[4])
                    ps.setString(6, p[5])
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            val todayNpt = LocalDate.now(ZoneId.of("Asia/Kathmandu"))
            val cities = listOf("KTM", "PKR")
            val labels = listOf("07:00-09:00", "09:00-11:00", "11:00-13:00")
            val firstDayIso = todayNpt.plusDays(1).toString()
            conn.prepareStatement(
                """
                INSERT INTO slots (id, city, date_iso, label, capacity, booked_count)
                VALUES (?, ?, ?, ?, 6, 0)
                ON CONFLICT (city, date_iso, label) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                for (day in 1..5) {
                    val dateIso = todayNpt.plusDays(day.toLong()).toString()
                    for (city in cities) {
                        for (label in labels) {
                            ps.setString(1, "${city}_${dateIso}_${label}")
                            ps.setString(2, city)
                            ps.setString(3, dateIso)
                            ps.setString(4, label)
                            ps.addBatch()
                        }
                    }
                }
                ps.executeBatch()
            }

            conn.prepareStatement(
                """
                INSERT INTO users (id, phone, full_name, role, created_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (phone) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, "+9779800000002")
                ps.setString(3, "Staff Picker")
                ps.setString(4, "PICKER")
                ps.setLong(5, now)
                ps.addBatch()

                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, "+9779841000001")
                ps.setString(3, "Sita Sharma")
                ps.setString(4, "PATIENT")
                ps.setLong(5, now)
                ps.addBatch()

                ps.executeBatch()
            }

            val hasPickups = conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM pickups").use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
            if (!hasPickups) {
                val pickerId = conn.prepareStatement("SELECT id FROM users WHERE phone = '+9779800000002'").use { ps ->
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
                val demoPatientId = conn.prepareStatement("SELECT id FROM users WHERE phone = '+9779841000001'").use { ps ->
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
                val slotId = "KTM_${firstDayIso}_07:00-09:00"
                if (pickerId != null && demoPatientId != null) {
                    val bookingId = "seed-booking-101"
                    conn.prepareStatement(
                        """
                        INSERT INTO bookings (id, user_id, package_id, slot_id, city, address_line, total_npr, status, created_at_ms, updated_at_ms)
                        VALUES (?, ?, 'p-basic', ?, 'KTM', 'Jhamsikhel, Patan', 4500, 'PICKER_ASSIGNED', ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.setString(2, demoPatientId)
                        ps.setString(3, slotId)
                        ps.setLong(4, now)
                        ps.setLong(5, now)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        """
                        INSERT INTO pickups (id, booking_id, picker_id, status, updated_at_ms)
                        VALUES ('pk-seed-101', ?, ?, 'ASSIGNED', ?)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, bookingId)
                        ps.setString(2, pickerId)
                        ps.setLong(3, now)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }
}
