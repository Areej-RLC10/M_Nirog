package com.example.m_nirog.data

import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.core.NepalTime
import com.example.m_nirog.models.Booking
import com.example.m_nirog.models.BookingListItem
import com.example.m_nirog.models.BookingRequest
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.CheckupPackage
import com.example.m_nirog.models.City
import com.example.m_nirog.models.SlotDay
import com.example.m_nirog.models.TimeSlot
import com.example.m_nirog.models.TimelineSteps
import com.example.m_nirog.models.timelineAnchor
import java.util.UUID
import kotlinx.coroutines.delay

interface BookingRepository {
    suspend fun getPackages(): List<CheckupPackage>
    suspend fun getDays(): List<SlotDay>
    suspend fun getSlots(city: City, dateIso: String): List<TimeSlot>
    suspend fun createBooking(request: BookingRequest, dateLabel: String, slotLabel: String): Booking
    suspend fun getBooking(bookingId: String): Booking
    suspend fun getMyBookings(): List<BookingListItem>
    suspend fun cancelBooking(bookingId: String): Booking
    suspend fun expireBooking(bookingId: String): Booking
    suspend fun devSetStatus(bookingId: String, status: BookingStatus): Booking
    suspend fun devAdvance(bookingId: String): Booking
}

class FakeBookingRepository : BookingRepository {
    private val packages = listOf(
        CheckupPackage("p-basic", "BASIC_HEALTH", "Basic Health", "Whole body checkup", 450000, true),
        CheckupPackage("p-ncd", "NCD", "NCD", "Coming soon", 0, false),
        CheckupPackage("p-joint", "JOINT_PAIN", "Joint Pain", "Coming soon", 0, false),
    )

    override suspend fun getPackages(): List<CheckupPackage> {
        delay(300)
        return packages
    }

    override suspend fun getDays(): List<SlotDay> = listOf(
        SlotDay("2026-11-25", "Wed 25 Nov"),
        SlotDay("2026-11-26", "Thu 26 Nov"),
        SlotDay("2026-11-27", "Fri 27 Nov"),
    )

    override suspend fun getSlots(city: City, dateIso: String): List<TimeSlot> {
        delay(200)
        return listOf(
            TimeSlot("${city.code}-$dateIso-0700", "07:00-09:00", 3),
            TimeSlot("${city.code}-$dateIso-0900", "09:00-11:00", 2),
            TimeSlot("${city.code}-$dateIso-1100", "11:00-13:00", 0),
        )
    }

    override suspend fun createBooking(request: BookingRequest, dateLabel: String, slotLabel: String): Booking {
        delay(400)
        seedIfNeeded()
        val pkg = packages.first { it.id == request.packageId }
        val booking = Booking(
            id = UUID.randomUUID().toString(),
            packageName = pkg.name,
            pricePaisa = pkg.pricePaisa,
            dateLabel = dateLabel,
            slotLabel = slotLabel,
            address = request.address,
            status = BookingStatus.PENDING_PAYMENT,
            holdExpiresAtMillis = System.currentTimeMillis() + 15 * 60 * 1000,
        )
        sharedBookings[booking.id] = booking
        return booking
    }

    override suspend fun getBooking(bookingId: String): Booking {
        seedIfNeeded()
        return update(bookingId) { it }
    }

    override suspend fun getMyBookings(): List<BookingListItem> {
        seedIfNeeded()
        val updated = NepalTime.formatDateTime(System.currentTimeMillis())
        return sharedBookings.values.reversed().map { b ->
            BookingListItem(
                id = b.id,
                packageName = b.packageName,
                dateLabel = b.dateLabel,
                slotLabel = b.slotLabel,
                status = b.status,
                lastUpdatedLabel = updated,
            )
        }
    }

    override suspend fun cancelBooking(bookingId: String): Booking {
        delay(300)
        return update(bookingId) {
            if (it.status == BookingStatus.PENDING_PAYMENT || it.status == BookingStatus.PAID) {
                it.copy(status = BookingStatus.CANCELLED)
            } else {
                throw AppError("CANCEL_NOT_ALLOWED")
            }
        }
    }

    override suspend fun expireBooking(bookingId: String): Booking = update(bookingId) {
        if (it.status == BookingStatus.PENDING_PAYMENT) it.copy(status = BookingStatus.EXPIRED) else it
    }

    override suspend fun devSetStatus(bookingId: String, status: BookingStatus): Booking =
        update(bookingId) { it.copy(status = status) }

    override suspend fun devAdvance(bookingId: String): Booking = update(bookingId) { b ->
        val next = if (b.status == BookingStatus.PENDING_PAYMENT) {
            BookingStatus.PAID
        } else {
            val i = TimelineSteps.indexOf(b.status.timelineAnchor)
            if (i < 0) b.status else TimelineSteps.getOrElse(i + 1) { b.status }
        }
        b.copy(
            status = next,
            pickerName = if (next == BookingStatus.PICKER_ASSIGNED) "[PICKER NAME]" else b.pickerName,
            pickerPhone = if (next == BookingStatus.PICKER_ASSIGNED) "98XXXXXXXX" else b.pickerPhone,
        )
    }

    private fun update(id: String, change: (Booking) -> Booking): Booking {
        val current = sharedBookings[id] ?: throw AppError("NOT_FOUND")
        return change(current).also { sharedBookings[id] = it }
    }

    companion object {
        private val sharedBookings = linkedMapOf<String, Booking>()

        private fun seedIfNeeded() {
            if (!DevConfig.ENABLED || sharedBookings.isNotEmpty()) return
            sharedBookings["demo-verify"] = Booking(
                id = "demo-verify",
                packageName = "Basic Health",
                pricePaisa = 450000,
                dateLabel = "Wed 25 Nov",
                slotLabel = "07:00-09:00",
                address = "Patan, Lalitpur",
                status = BookingStatus.AWAITING_VERIFICATION,
                holdExpiresAtMillis = System.currentTimeMillis() + 15 * 60 * 1000,
            )
            sharedBookings["demo-done"] = Booking(
                id = "demo-done",
                packageName = "Basic Health",
                pricePaisa = 450000,
                dateLabel = "Mon 16 Nov",
                slotLabel = "09:00-11:00",
                address = "Lakeside, Pokhara",
                status = BookingStatus.COMPLETED,
                holdExpiresAtMillis = System.currentTimeMillis(),
            )
        }
    }
}
