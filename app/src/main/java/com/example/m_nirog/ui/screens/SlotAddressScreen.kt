package com.example.m_nirog.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.m_nirog.models.City
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel

@Composable
fun SlotAddressScreen(vm: BookingViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.loadDays() }

    NirogScaffold("Date, time and address", onBack) {
        Text("City", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            City.entries.forEach { c ->
                FilterChip(selected = s.city == c, onClick = { vm.setCity(c) }, label = { Text(c.label) })
            }
        }

        Text("Date", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.days.forEach { d ->
                FilterChip(selected = s.day == d, onClick = { vm.setDay(d) }, label = { Text(d.label) })
            }
        }

        Text("Time slot", style = MaterialTheme.typography.titleSmall)
        if (s.city == null || s.day == null) {
            Text("Choose a city and date first.")
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.slots.forEach { slot ->
                    FilterChip(
                        selected = s.slot?.id == slot.id,
                        enabled = !slot.isFull,
                        onClick = { vm.setSlot(slot) },
                        label = { Text(if (slot.isFull) "${slot.label} · Full" else slot.label) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = s.address,
            onValueChange = vm::setAddress,
            label = { Text("Address") },
            placeholder = { Text("Street, area, landmark") },
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(s.error)
        PrimaryButton("Continue", onContinue, enabled = s.canContinue)
    }
}
