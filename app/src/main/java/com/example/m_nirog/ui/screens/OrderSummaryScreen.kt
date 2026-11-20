package com.example.m_nirog.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.R
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.KhaltiWebViewDialog
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.BookingViewModel
import kotlinx.coroutines.delay

@Composable
fun OrderSummaryScreen(
    vm: BookingViewModel,
    onBack: () -> Unit,
    onVerifyingPayment: () -> Unit = {},
    onPaid: () -> Unit,
) {
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
    LaunchedEffect(holdOver) { if (holdOver) vm.expire() }

    val khaltiSession = s.khaltiSession
    if (khaltiSession != null) {
        KhaltiWebViewDialog(
            session = khaltiSession,
            onCallback = { pidx, status ->
                vm.onKhaltiRedirect(pidx, status)
                onVerifyingPayment()
            },
            onDismiss = vm::dismissKhaltiCheckout,
        )
    }

    NirogScaffold(stringResource(R.string.order_summary_title), onBack = if (booking == null) onBack else null) {
        InfoCard {
            SummaryRow("Package", s.selectedPackage?.name.orEmpty())
            SummaryRow("Date", s.day?.label.orEmpty())
            SummaryRow("Slot", s.slot?.label.orEmpty())
            SummaryRow("Address", s.address)
            SummaryRow("Total", s.selectedPackage?.priceLabel.orEmpty())
        }
        ErrorText(s.error)
        when (booking?.status) {
            null -> PrimaryButton(stringResource(R.string.proceed_to_pay), vm::createBooking, loading = s.busy)
            BookingStatus.PENDING_PAYMENT -> {
                InfoCard {
                    Text(
                        stringResource(R.string.slot_held_for, (holdLeft / 60).toInt(), (holdLeft % 60).toInt()),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.slot_hold_hint))
                }
                PrimaryButton(
                    "Pay with Khalti",
                    onClick = vm::payWithKhalti,
                    enabled = holdLeft > 0,
                    loading = s.busy,
                )
            }
            BookingStatus.EXPIRED -> {
                InfoCard {
                    Text(stringResource(R.string.slot_hold_expired_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.slot_hold_expired_body))
                }
                PrimaryButton(stringResource(R.string.choose_another_slot), onClick = { vm.startOver(); onBack() })
            }
            else -> Unit
        }
    }
}
