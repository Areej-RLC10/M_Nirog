package com.example.m_nirog.models

enum class City(val code: String, val label: String) {
    KTM("KTM", "Kathmandu"),
    PKR("PKR", "Pokhara"),
}

data class SlotDay(val isoDate: String, val label: String)

data class TimeSlot(val id: String, val label: String, val remaining: Int) {
    val isFull: Boolean get() = remaining <= 0
}

/** Mirrors the server status list. UNKNOWN keeps the app alive if the server adds a status. */
enum class BookingStatus(val patientLabel: String) {
    PENDING_PAYMENT("Payment pending"),
    PAID("Booking confirmed"),
    PICKER_ASSIGNED("Picker assigned"),
    SAMPLE_COLLECTED("Sample collected"),
    AT_LAB("Sample at lab"),
    RECOLLECTION_REQUIRED("New sample needed"),
    REPORT_UPLOADED("Report ready"),
    AWAITING_VERIFICATION("Please verify your results"),
    CORRECTION_REQUIRED("Correction in progress"),
    DOCTOR_REVIEW("Doctor reviewing"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired"),
    PATIENT_NO_SHOW("Pickup missed"),
    UNKNOWN("Updating");

    companion object {
        fun fromServer(value: String): BookingStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Steps shown on the status timeline, in happy-path order. */
val TimelineSteps = listOf(
    BookingStatus.PAID,
    BookingStatus.PICKER_ASSIGNED,
    BookingStatus.SAMPLE_COLLECTED,
    BookingStatus.AT_LAB,
    BookingStatus.REPORT_UPLOADED,
    BookingStatus.AWAITING_VERIFICATION,
    BookingStatus.DOCTOR_REVIEW,
    BookingStatus.COMPLETED,
)

data class Booking(
    val id: String,
    val packageName: String,
    val pricePaisa: Int,
    val dateLabel: String,
    val slotLabel: String,
    val address: String,
    val status: BookingStatus,
    val holdExpiresAtMillis: Long,
    val pickerName: String? = null,
    val pickerPhone: String? = null,
)

data class BookingRequest(
    val packageId: String,
    val city: City,
    val dateIso: String,
    val slotId: String,
    val address: String,
)

/** Statuses where the booking is over. The patient sees a message, not a timeline. */
val BookingStatus.isFinal: Boolean
    get() = this == BookingStatus.CANCELLED ||
        this == BookingStatus.EXPIRED ||
        this == BookingStatus.PATIENT_NO_SHOW

/** Which timeline step to highlight for statuses that are not on the happy path. */
val BookingStatus.timelineAnchor: BookingStatus
    get() = when (this) {
        BookingStatus.RECOLLECTION_REQUIRED -> BookingStatus.PICKER_ASSIGNED
        BookingStatus.CORRECTION_REQUIRED -> BookingStatus.AWAITING_VERIFICATION
        else -> this
    }
