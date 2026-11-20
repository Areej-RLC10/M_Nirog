package com.example.m_nirog.models

enum class PickerJobStatus(val label: String) {
    ASSIGNED("Assigned"),
    ARRIVED("At address · Waiting"),
    COLLECTED("Sample collected"),
    NO_SHOW("Patient no-show"),
}

data class PickerPickup(
    val id: String,
    val bookingId: String,
    val patientName: String,
    val patientPhone: String,
    val packageName: String,
    val dateLabel: String,
    val slotLabel: String,
    val address: String,
    val city: City,
    val status: PickerJobStatus = PickerJobStatus.ASSIGNED,
    val sampleCode: String? = null,
    val arrivedAtMillis: Long? = null,
    val noShowPhotoUri: String? = null,
)
