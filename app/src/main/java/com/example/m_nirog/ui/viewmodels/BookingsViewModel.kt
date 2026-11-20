package com.example.m_nirog.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.m_nirog.data.BookingRepository
import com.example.m_nirog.data.FakeBookingRepository
import com.example.m_nirog.data.friendlyMessage
import com.example.m_nirog.models.BookingListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookingsUiState(
    val items: List<BookingListItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

class BookingsViewModel(
    private val repo: BookingRepository = FakeBookingRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(BookingsUiState())
    val state: StateFlow<BookingsUiState> = _state.asStateFlow()

    fun reset() { _state.value = BookingsUiState() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val list = repo.getMyBookings()
                _state.update { it.copy(items = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendlyMessage()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
