package com.example.m_nirog.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.data.BookingRepository
import com.example.m_nirog.data.FakeBookingRepository
import com.example.m_nirog.data.RemoteBookingRepository
import com.example.m_nirog.data.friendlyMessage
import com.example.m_nirog.models.Booking
import com.example.m_nirog.models.BookingRequest
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.CheckupPackage
import com.example.m_nirog.models.City
import com.example.m_nirog.models.KhaltiCheckoutSession
import com.example.m_nirog.models.SlotDay
import com.example.m_nirog.models.TimeSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookingUiState(
    val packages: List<CheckupPackage> = emptyList(),
    val selectedPackage: CheckupPackage? = null,
    val days: List<SlotDay> = emptyList(),
    val city: City? = null,
    val day: SlotDay? = null,
    val slots: List<TimeSlot> = emptyList(),
    val slot: TimeSlot? = null,
    val address: String = "",
    val booking: Booking? = null,
    val khaltiSession: KhaltiCheckoutSession? = null,
    val pendingKhaltiPidx: String? = null,
    val paymentStatusMessage: String? = null,
    val paymentFailed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canContinue: Boolean get() = city != null && day != null && slot != null && address.isNotBlank()
}

class BookingViewModel(
    private val repo: BookingRepository = FakeBookingRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(BookingUiState())
    val state: StateFlow<BookingUiState> = _state.asStateFlow()

    private fun launchWork(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendlyMessage()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun loadPackages() {
        if (_state.value.packages.isNotEmpty()) return
        launchWork { repo.getPackages().let { list -> _state.update { it.copy(packages = list) } } }
    }

    fun selectPackage(p: CheckupPackage) = _state.update { it.copy(selectedPackage = p) }

    fun loadDays() {
        if (_state.value.days.isNotEmpty()) return
        launchWork { repo.getDays().let { list -> _state.update { it.copy(days = list) } } }
    }

    fun setCity(c: City) {
        _state.update { it.copy(city = c, slot = null) }
        refreshSlots()
    }

    fun setDay(d: SlotDay) {
        _state.update { it.copy(day = d, slot = null) }
        refreshSlots()
    }

    private fun refreshSlots() {
        val s = _state.value
        val city = s.city ?: return
        val day = s.day ?: return
        launchWork { repo.getSlots(city, day.isoDate).let { list -> _state.update { it.copy(slots = list) } } }
    }

    fun setSlot(slot: TimeSlot) = _state.update { it.copy(slot = slot) }
    fun setAddress(value: String) = _state.update { it.copy(address = value) }

    fun createBooking() {
        val s = _state.value
        val pkg = s.selectedPackage ?: return
        val city = s.city ?: return
        val day = s.day ?: return
        val slot = s.slot ?: return
        launchWork {
            val request = BookingRequest(pkg.id, city, day.isoDate, slot.id, s.address.trim())
            val booking = repo.createBooking(request, day.label, slot.label)
            _state.update { it.copy(booking = booking, paymentFailed = false, paymentStatusMessage = null) }
        }
    }

    fun openBooking(bookingId: String) {
        launchWork {
            val b = repo.getBooking(bookingId)
            _state.update { it.copy(booking = b) }
        }
    }

    fun payWithKhalti() {
        val id = _state.value.booking?.id ?: return
        val remote = repo as? RemoteBookingRepository ?: run {
            _state.update { it.copy(error = "Live payment requires server connection.") }
            return
        }
        launchWork {
            val session = remote.initiateKhalti(id)
            _state.update {
                it.copy(
                    khaltiSession = session,
                    pendingKhaltiPidx = session.pidx,
                    paymentFailed = false,
                    paymentStatusMessage = null,
                )
            }
        }
    }

    fun dismissKhaltiCheckout() {
        _state.update {
            it.copy(
                khaltiSession = null,
                error = "Khalti checkout closed before completion. Your slot is still held.",
            )
        }
    }

    fun onKhaltiRedirect(pidxFromUrl: String?, statusFromUrl: String?) {
        val resolvedPidx = pidxFromUrl ?: _state.value.pendingKhaltiPidx
        _state.update {
            it.copy(
                khaltiSession = null,
                pendingKhaltiPidx = resolvedPidx,
                paymentStatusMessage = statusFromUrl?.let { st -> "Khalti returned: $st. Verifying on server..." },
            )
        }
        verifyKhaltiPayment(resolvedPidx)
    }

    fun verifyKhaltiPayment(pidxOverride: String? = null) {
        val id = _state.value.booking?.id ?: return
        val remote = repo as? RemoteBookingRepository ?: return
        val pidx = pidxOverride ?: _state.value.pendingKhaltiPidx
        launchWork {
            val res = remote.verifyKhalti(id, pidx)
            val terminalFailure = res.booking.status != BookingStatus.PAID &&
                res.paymentStatus in setOf("Expired", "User canceled", "Failed", "Refunded")
            _state.update {
                it.copy(
                    booking = res.booking,
                    pendingKhaltiPidx = if (res.booking.status == BookingStatus.PAID || terminalFailure) null else pidx,
                    paymentFailed = terminalFailure || res.booking.status == BookingStatus.EXPIRED,
                    paymentStatusMessage = when {
                        res.booking.status == BookingStatus.PAID -> null
                        terminalFailure -> "Payment status: ${res.paymentStatus}"
                        else -> "Khalti status: ${res.paymentStatus}. Tap Refresh status in a moment."
                    },
                )
            }
        }
    }

    fun refreshOrVerifyKhalti() {
        if (!_state.value.pendingKhaltiPidx.isNullOrBlank()) {
            verifyKhaltiPayment()
        } else {
            refresh()
        }
    }

    fun consumePaymentFailed() = _state.update { it.copy(paymentFailed = false) }

    fun cancel() = updateBooking { repo.cancelBooking(it) }
    fun refresh() = updateBooking { repo.getBooking(it) }
    fun expire() = updateBooking { repo.expireBooking(it) }

    fun devAdvance() {
        if (!DevConfig.ENABLED) return
        updateBooking { repo.devAdvance(it) }
    }

    fun devSet(status: BookingStatus) {
        if (!DevConfig.ENABLED) return
        updateBooking { repo.devSetStatus(it, status) }
    }

    fun devUrgent() {
        if (!DevConfig.ENABLED) return
        updateBooking { id ->
            (repo as? RemoteBookingRepository)?.devSetUrgent(id)
                ?: repo.devSetStatus(id, BookingStatus.COMPLETED)
        }
    }

    fun startOver() = _state.update {
        it.copy(
            booking = null,
            slot = null,
            khaltiSession = null,
            pendingKhaltiPidx = null,
            paymentStatusMessage = null,
            paymentFailed = false,
            error = null,
        )
    }

    private fun updateBooking(call: suspend (String) -> Booking) {
        val id = _state.value.booking?.id ?: return
        launchWork { call(id).let { b -> _state.update { it.copy(booking = b) } } }
    }

    fun reset() {
        _state.value = BookingUiState(packages = _state.value.packages, days = _state.value.days)
    }
}
