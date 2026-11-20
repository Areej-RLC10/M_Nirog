package com.mnirog.backend

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)

@Serializable
data class SendOtpRequest(
    val phone: String,
)

@Serializable
data class SendOtpResponse(
    val sent: Boolean,
    val phone: String,
    val devOtp: String? = null,
)

@Serializable
data class VerifyOtpRequest(
    val phone: String,
    val code: String,
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val phone: String,
    val role: String,
    val consentVersion: String?,
)

@Serializable
data class ConsentRequest(
    val version: String,
)

@Serializable
data class PackageDto(
    val id: String,
    val name: String,
    val priceNpr: Int,
    val pricePaisa: Int,
    val fastingHours: Int,
    val description: String,
    val includedTests: List<String>,
)

@Serializable
data class SlotDayDto(
    val dateIso: String,
    val label: String,
)

@Serializable
data class SlotDto(
    val id: String,
    val city: String,
    val dateIso: String,
    val label: String,
    val remaining: Int,
)

@Serializable
data class CreateBookingRequest(
    val packageId: String,
    val slotId: String,
    val city: String,
    val addressLine: String,
)

@Serializable
data class BookingDto(
    val id: String,
    val packageId: String,
    val packageName: String,
    val slotId: String,
    val city: String,
    val dateLabel: String,
    val slotLabel: String,
    val addressLine: String,
    val totalNpr: Int,
    val pricePaisa: Int,
    val status: String,
    val holdExpiresAtMs: Long?,
    val updatedAtMs: Long,
    val pickerName: String? = null,
    val pickerPhone: String? = null,
    val recollectionReason: String? = null,
    val recollectionCount: Int = 0,
    val verificationDeadlineMs: Long? = null,
    val isUrgent: Boolean = false,
)

@Serializable
data class AddressDto(
    val id: String,
    val city: String,
    val street: String,
    val landmark: String = "",
)

@Serializable
data class ProfileDto(
    val fullName: String,
    val dob: String,
    val gender: String,
    val addresses: List<AddressDto>,
)

@Serializable
data class UpdateProfileRequest(
    val fullName: String,
    val dob: String,
    val gender: String,
)

@Serializable
data class CreateAddressRequest(
    val city: String,
    val street: String,
    val landmark: String = "",
)

@Serializable
data class HealthRecordDto(
    val id: String,
    val title: String,
    val recordType: String,
    val recordDate: String,
    val fileUrl: String?,
    val mimeType: String,
    val createdAtMs: Long,
)

@Serializable
data class CreateHealthRecordRequest(
    val title: String,
    val recordType: String,
    val recordDate: String,
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val fileBase64: String = "",
)

@Serializable
data class PickerPickupDto(
    val id: String,
    val bookingId: String,
    val patientName: String,
    val patientPhone: String,
    val packageName: String,
    val dateLabel: String,
    val slotLabel: String,
    val address: String,
    val city: String,
    val status: String,
    val sampleCode: String? = null,
    val arrivedAtMs: Long? = null,
    val hasPhotoProof: Boolean = false,
)

@Serializable
data class CollectSampleRequest(
    val sampleCode: String,
)

@Serializable
data class NoShowRequest(
    val photoBase64: String,
)

@Serializable
data class LabValueDto(
    val id: String,
    val name: String,
    val value: String,
    val unit: String,
    val referenceLow: String,
    val referenceHigh: String,
    val flag: String = "NORMAL",
    val flaggedByPatient: Boolean = false,
    val patientNote: String? = null,
)

@Serializable
data class ReportDetailDto(
    val bookingId: String,
    val packageName: String,
    val dateLabel: String,
    val values: List<LabValueDto>,
    val doctorName: String? = null,
    val doctorNmcNumber: String? = null,
    val doctorNote: String? = null,
    val isUrgent: Boolean = false,
    val verificationDeadlineMs: Long? = null,
)

@Serializable
data class FlagValueRequest(
    val valueId: String,
    val note: String,
)

@Serializable
data class DevStatusRequest(
    val status: String,
    val urgent: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class KhaltiInitiateResponse(
    val pidx: String,
    val paymentUrl: String,
    val returnUrl: String,
    val amountPaisa: Int,
    val expiresAt: String? = null,
)

@Serializable
data class KhaltiVerifyRequest(
    val pidx: String? = null,
)

@Serializable
data class KhaltiVerifyResponse(
    val paymentStatus: String,
    val transactionId: String? = null,
    val booking: BookingDto,
)

@Serializable
data class FcmTokenRequest(
    val token: String,
)

@Serializable
data class NotificationDto(
    val id: String,
    val bookingId: String? = null,
    val title: String,
    val body: String,
    val category: String,
    val isRead: Boolean,
    val createdAtMs: Long,
)

@Serializable
data class NotificationsResponse(
    val unreadCount: Int,
    val items: List<NotificationDto>,
)
