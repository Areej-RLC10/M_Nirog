package com.example.m_nirog.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.core.ApiClient
import com.example.m_nirog.core.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class AuthState(
    val phone: String = "",
    val otp: String = "",
    val otpSent: Boolean = false,
    val devOtp: String? = null,
    val loggedIn: Boolean = false,
    val consented: Boolean = false,
    val role: String = "PATIENT",
    val busy: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = ApiClient(store)

    private val _state = MutableStateFlow(
        AuthState(
            loggedIn = store.isLoggedIn() && !store.accessToken().isNullOrBlank(),
            consented = store.consentVersion() == CONSENT_VERSION,
            role = store.role(),
        )
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun onPhone(value: String) = _state.update {
        val cleaned = value.filter(Char::isDigit).take(10)
        if (cleaned == it.phone) it else it.copy(phone = cleaned, otpSent = false, otp = "", devOtp = null, error = null)
    }
    fun changePhone() = _state.update { it.copy(otpSent = false, otp = "", devOtp = null, error = null) }
    fun onOtp(value: String) = _state.update { it.copy(otp = value.filter(Char::isDigit).take(6), error = null) }

    fun sendOtp() {
        val phone = _state.value.phone
        if (phone.length != 10 || !phone.startsWith("9")) {
            _state.update { it.copy(error = "Enter a valid 10-digit mobile number") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val res = api.post("/auth/otp/send", JSONObject().put("phone", phone))
                JSONObject(res)
            }.onSuccess { json ->
                val devOtp = json.optString("devOtp").takeIf { it.isNotBlank() }
                _state.update { it.copy(otpSent = true, devOtp = devOtp, busy = false, error = null) }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not send OTP") }
            }
        }
    }

    fun verify() {
        val s = _state.value
        if (s.otp.length != 6) {
            _state.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val res = api.post(
                    "/auth/otp/verify",
                    JSONObject().put("phone", s.phone).put("code", s.otp),
                )
                JSONObject(res)
            }.onSuccess { json ->
                val access = json.getString("accessToken")
                val refresh = json.getString("refreshToken")
                val role = json.optString("role", "PATIENT")
                val serverConsent = json.optString("consentVersion").takeIf { it.isNotBlank() }
                store.saveTokens(access, refresh)
                store.saveRole(role)
                store.setLoggedIn(true)
                if (serverConsent == CONSENT_VERSION) {
                    store.saveConsent(CONSENT_VERSION)
                }
                _state.update {
                    it.copy(
                        loggedIn = true,
                        consented = serverConsent == CONSENT_VERSION || store.consentVersion() == CONSENT_VERSION,
                        role = role,
                        busy = false,
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Invalid OTP") }
            }
        }
    }

    fun acceptConsent() {
        viewModelScope.launch {
            runCatching {
                api.post("/auth/consent", JSONObject().put("version", CONSENT_VERSION))
            }
            store.saveConsent(CONSENT_VERSION)
            _state.update { it.copy(consented = true) }
        }
    }

    fun logout() {
        store.clear()
        _state.value = AuthState()
    }

    companion object {
        const val CONSENT_VERSION = "draft-0"
    }
}
