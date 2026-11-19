package com.example.m_nirog.data

/** Same codes as the backend error format: { "error": { "code": ... } }. */
class AppError(val code: String) : Exception(code)

fun Throwable.friendlyMessage(): String = when ((this as? AppError)?.code) {
    "SLOT_FULL" -> "That slot just filled. Please pick another time."
    "CANCEL_NOT_ALLOWED" -> "A picker has been assigned, so this booking can no longer be cancelled."
    "HOLD_EXPIRED" -> "Your slot hold expired. Please book again."
    "PAYMENT_NOT_VERIFIED" -> "We could not confirm your payment yet. Please try again."
    "NOT_FOUND" -> "We could not find that booking."
    else -> "Something went wrong. Please try again."
}
