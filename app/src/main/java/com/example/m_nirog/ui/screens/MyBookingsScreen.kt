package com.example.m_nirog.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.R
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.BookingsViewModel

@Composable
fun MyBookingsScreen(
    vm: BookingsViewModel,
    onBookCheckup: () -> Unit,
    onOpenBooking: (String) -> Unit,
) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    NirogScaffold(stringResource(R.string.my_bookings_title)) {
        ErrorText(s.error)
        if (s.items.isEmpty()) {
            InfoCard {
                Text(stringResource(R.string.no_bookings_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.no_bookings_body))
                PrimaryButton(stringResource(R.string.book_a_checkup), onBookCheckup)
            }
        } else {
            s.items.forEach { item ->
                Box(modifier = Modifier.fillMaxWidth().clickable { onOpenBooking(item.id) }) {
                    InfoCard {
                        SummaryRow(item.packageName, item.dateLabel)
                        SummaryRow("Slot", item.slotLabel)
                        SummaryRow("Status", item.status.patientLabel)
                        Text(item.lastUpdatedLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
