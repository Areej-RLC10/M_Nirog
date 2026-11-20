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
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel
import kotlinx.coroutines.delay

@Composable
fun PaymentFailedScreen(
    vm: BookingViewModel,
    onTryAgain: () -> Unit,
    onChooseAnotherSlot: () -> Unit,
) {
    val s by vm.state.collectAsState()
    val booking = s.booking
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val holdLeft = if (booking == null) 0L else ((booking.holdExpiresAtMillis - now) / 1000).coerceAtLeast(0)

    LaunchedEffect(booking?.id) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    NirogScaffold("Payment not completed") {
        Text("Your payment did not go through", fontWeight = FontWeight.SemiBold)
        if (!s.paymentStatusMessage.isNullOrBlank()) {
            Text(s.paymentStatusMessage!!)
        }
        Text(
            if (holdLeft > 0) "You were not charged. Your slot is held for %d:%02d. After that it is released.".format(holdLeft / 60, holdLeft % 60)
            else "Your slot hold has expired and the slot has been released."
        )
        if (holdLeft > 0) {
            PrimaryButton("Try again", onClick = onTryAgain, loading = s.busy)
        }
        SecondaryButton("Choose another slot", onClick = { vm.startOver(); onChooseAnotherSlot() })
        Text("If money left your account, it is confirmed or refunded automatically.")
    }
}
