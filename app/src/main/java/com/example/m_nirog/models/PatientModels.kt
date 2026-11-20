package com.example.m_nirog.models

enum class ResultFlag(val label: String) {
    LOW("Low"),
    NORMAL("Normal"),
    HIGH("High"),
    CRITICAL("Critical"),
}

data class LabValue(
    val id: String,
    val name: String,
    val value: String,
    val unit: String,
    val referenceLow: String,
    val referenceHigh: String,
    val flag: ResultFlag = ResultFlag.NORMAL,
    val flaggedByPatient: Boolean = false,
    val patientNote: String? = null,
)

data class ReportDetail(
    val bookingId: String,
    val packageName: String,
    val dateLabel: String,
    val pdfUrl: String?,
    val values: List<LabValue>,
    val doctorName: String? = null,
    val doctorNmcNumber: String? = null,
    val doctorNote: String? = null,
    val isUrgent: Boolean = false,
)

data class BookingListItem(
    val id: String,
    val packageName: String,
    val dateLabel: String,
    val slotLabel: String,
    val status: BookingStatus,
    val lastUpdatedLabel: String,
)

data class SavedAddress(
    val id: String,
    val city: City,
    val street: String,
)

data class PatientProfile(
    val fullName: String = "",
    val dob: String = "",
    val gender: String = "",
    val addresses: List<SavedAddress> = emptyList(),
    val phone: String = "",
)

enum class RecordType(val label: String) {
    LAB_REPORT("Lab report"),
    PRESCRIPTION("Prescription"),
    OTHER("Other"),
}

data class HealthRecord(
    val id: String,
    val title: String,
    val type: RecordType,
    val dateLabel: String,
    val fileUrl: String?,
)

data class KhaltiCheckoutSession(
    val pidx: String,
    val paymentUrl: String,
    val returnUrl: String,
    val amountPaisa: Int,
)

data class KhaltiVerifyResult(
    val paymentStatus: String,
    val transactionId: String?,
    val booking: Booking,
)

data class NotificationItem(
    val id: String,
    val bookingId: String?,
    val title: String,
    val body: String,
    val category: String,
    val isRead: Boolean,
    val createdAtMs: Long,
)
