package com.example.m_nirog.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton

@Composable
fun NewSampleNeededScreen(
    labReason: String,
    onBack: () -> Unit,
    onViewBooking: () -> Unit,
) {
    NirogScaffold("New sample needed", onBack = onBack) {
        Text("The lab could not use your sample", fontWeight = FontWeight.SemiBold)
        Text("Reason: $labReason. A new pickup is free. We will confirm a new time shortly.")
        PrimaryButton("View booking", onViewBooking)
        Text("If a second sample is also rejected, you get a full refund.")
    }
}

@Composable
fun PickupMissedScreen(
    onBack: () -> Unit,
    onBookAgain: () -> Unit,
    onContactSupport: () -> Unit,
) {
    NirogScaffold("Pickup missed", onBack = onBack) {
        Text("We could not collect your sample", fontWeight = FontWeight.SemiBold)
        Text("The picker waited 15 minutes at your address. This booking is closed and cannot be refunded.")
        PrimaryButton("Book again", onBookAgain)
        SecondaryButton("Contact support", onContactSupport)
    }
}

@Composable
fun UrgentResultScreen(
    onOpenReport: () -> Unit,
) {
    NirogScaffold("Urgent: check your report") {
        InfoCard {
            Text("Please open your report now", fontWeight = FontWeight.SemiBold)
            Text("Our team will also phone you. If you feel unwell, seek emergency care.")
        }
        PrimaryButton("Open report", onOpenReport)
    }
}
