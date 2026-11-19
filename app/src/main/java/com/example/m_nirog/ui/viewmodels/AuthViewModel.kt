package com.example.m_nirog.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.m_nirog.core.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AuthState(
    val phone: String = "",
    val otp: String = "",
    val otpSent: Boolean = false,
    val loggedIn: Boolean = false,
    val consented: Boolean = false,
    val error: String? = null,
)

/** Slice 1 stub: any 6-digit code works. Replaced by the real OTP endpoints later. */
class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)

    private val _state = MutableStateFlow(
        AuthState(
            loggedIn = store.isLoggedIn(),
            consented = store.consentVersion() == CONSENT_VERSION,
        )
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun onPhone(value: String) = _state.update { it.copy(phone = value.filter(Char::isDigit).take(10), error = null) }
    fun onOtp(value: String) = _state.update { it.copy(otp = value.filter(Char::isDigit).take(6), error = null) }

    fun sendOtp() {
        val phone = _state.value.phone
        if (phone.length == 10 && phone.startsWith("9")) {
            _state.update { it.copy(otpSent = true, error = null) }
        } else {
            _state.update { it.copy(error = "Enter a valid 10-digit mobile number") }
        }
    }

    fun verify() {
        if (_state.value.otp.length == 6) {
            store.setLoggedIn(true)
            _state.update { it.copy(loggedIn = true, error = null) }
        } else {
            _state.update { it.copy(error = "Enter the 6-digit code") }
        }
    }

    /** Later: also POST /me/consents with the approved text version. */
    fun acceptConsent() {
        store.saveConsent(CONSENT_VERSION)
        _state.update { it.copy(consented = true) }
    }

    fun logout() {
        store.clear()
        _state.value = AuthState()
    }

    companion object {
        /** Change this when the lawyer-approved text replaces the draft. Everyone must accept again. */
        const val CONSENT_VERSION = "draft-0"
    }
}
