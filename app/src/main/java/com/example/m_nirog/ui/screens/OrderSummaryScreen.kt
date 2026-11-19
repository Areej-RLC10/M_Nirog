package com.example.m_nirog.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.BookingViewModel
import kotlinx.coroutines.delay

@Composable
fun OrderSummaryScreen(vm: BookingViewModel, onBack: () -> Unit, onPaid: () -> Unit) {
    val s by vm.state.collectAsState()
    val booking = s.booking
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pending = booking?.status == BookingStatus.PENDING_PAYMENT
    val holdLeft = if (booking == null) 0L else ((booking.holdExpiresAtMillis - now) / 1000).coerceAtLeast(0)
    val holdOver = pending && holdLeft == 0L

    LaunchedEffect(booking?.id) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(booking?.status) { if (booking?.status == BookingStatus.PAID) onPaid() }
    // The real server expires the booking itself. Here we ask the fake repository to do it.
    LaunchedEffect(holdOver) { if (holdOver) vm.expire() }

    NirogScaffold("Order summary", onBack = if (booking == null) onBack else null) {
        InfoCard {
            SummaryRow("Package", s.selectedPackage?.name.orEmpty())
            SummaryRow("Date", s.day?.label.orEmpty())
            SummaryRow("Slot", s.slot?.label.orEmpty())
            SummaryRow("Address", s.address)
            SummaryRow("Total", s.selectedPackage?.priceLabel.orEmpty())
        }
        ErrorText(s.error)
        when (booking?.status) {
            null -> PrimaryButton("Proceed to Pay", vm::createBooking, loading = s.busy)
            BookingStatus.PENDING_PAYMENT -> {
                Text("Your slot is held for %d:%02d".format(holdLeft / 60, holdLeft % 60))
                PrimaryButton("Pay with Khalti (fake)", vm::payFake, enabled = holdLeft > 0, loading = s.busy)
                Text("Slice 1 uses a fake payment. The real Khalti flow comes in Slice 2.")
            }
            BookingStatus.EXPIRED -> {
                InfoCard {
                    Text("Your slot hold expired", fontWeight = FontWeight.SemiBold)
                    Text("You were not charged. Please choose a time again.")
                }
                PrimaryButton("Choose another slot", onClick = { vm.startOver(); onBack() })
            }
            else -> Unit
        }
    }
}
