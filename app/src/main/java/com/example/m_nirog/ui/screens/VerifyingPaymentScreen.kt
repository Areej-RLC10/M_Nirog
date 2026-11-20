package com.example.m_nirog.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel

@Composable
fun VerifyingPaymentScreen(
    vm: BookingViewModel,
    onRefresh: () -> Unit,
    onPaid: () -> Unit,
    onPaymentFailed: () -> Unit,
) {
    val s by vm.state.collectAsState()
    val status = s.booking?.status

    LaunchedEffect(status, s.paymentFailed) {
        if (status == BookingStatus.PAID) onPaid()
        else if (s.paymentFailed || status == BookingStatus.EXPIRED) onPaymentFailed()
    }

    NirogScaffold("Verifying payment") {
        Text("Please wait. Do not close the app.")
        InfoCard {
            Text("Checking with Khalti...", fontWeight = FontWeight.SemiBold)
            Text(s.paymentStatusMessage ?: "This usually takes a few seconds. We confirm the payment on our server before showing your booking.")
        }
        ErrorText(s.error)
        SecondaryButton("Refresh status", onClick = onRefresh, enabled = !s.busy)
    }
}
