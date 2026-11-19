package com.example.m_nirog.data

import com.example.m_nirog.models.Booking
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
    suspend fun verifyPayment(bookingId: String): Booking
    suspend fun getBooking(bookingId: String): Booking
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
    private val bookings = mutableMapOf<String, Booking>()

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
        bookings[booking.id] = booking
        return booking
    }

    override suspend fun verifyPayment(bookingId: String): Booking {
        delay(800)
        return update(bookingId) { it.copy(status = BookingStatus.PAID) }
    }

    override suspend fun getBooking(bookingId: String): Booking = update(bookingId) { it }

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
        val current = bookings[id] ?: throw AppError("NOT_FOUND")
        return change(current).also { bookings[id] = it }
    }
}
