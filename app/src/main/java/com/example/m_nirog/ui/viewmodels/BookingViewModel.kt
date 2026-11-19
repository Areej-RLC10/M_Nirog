package com.example.m_nirog.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.data.BookingRepository
import com.example.m_nirog.data.FakeBookingRepository
import com.example.m_nirog.data.friendlyMessage
import com.example.m_nirog.models.Booking
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.BookingRequest
import com.example.m_nirog.models.CheckupPackage
import com.example.m_nirog.models.City
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
            _state.update { it.copy(booking = booking) }
        }
    }

    /** Slice 1 fake payment. Slice 2: open Khalti, then call verify, same as here. */
    fun payFake() = updateBooking { repo.verifyPayment(it) }
    fun cancel() = updateBooking { repo.cancelBooking(it) }
    fun devAdvance() = updateBooking { repo.devAdvance(it) }
    fun refresh() = updateBooking { repo.getBooking(it) }
    fun expire() = updateBooking { repo.expireBooking(it) }
    fun devSet(status: BookingStatus) = updateBooking { repo.devSetStatus(it, status) }

    /** After an expired hold: forget the booking and the chosen slot so the patient can pick again. */
    fun startOver() = _state.update { it.copy(booking = null, slot = null, error = null) }

    private fun updateBooking(call: suspend (String) -> Booking) {
        val id = _state.value.booking?.id ?: return
        launchWork { call(id).let { b -> _state.update { it.copy(booking = b) } } }
    }

    fun reset() {
        _state.value = BookingUiState(packages = _state.value.packages, days = _state.value.days)
    }
}
