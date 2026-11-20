package com.example.m_nirog.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel

@Composable
fun PackagesScreen(vm: BookingViewModel, onSelect: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.loadPackages() }

    NirogScaffold("Choose a checkup") {
        ErrorText(s.error)
        if (!s.error.isNullOrBlank() && s.packages.isEmpty() && !s.busy) {
            SecondaryButton("Try again", onClick = vm::loadPackages)
        }
        s.packages.forEach { pkg ->
            InfoCard {
                Text(pkg.name, fontWeight = FontWeight.SemiBold)
                Text("${pkg.description} · ${pkg.priceLabel}")
                Text("Includes: ${pkg.includedTests.joinToString(", ")}")
                PrimaryButton("Select package", onClick = { vm.selectPackage(pkg); onSelect() })
            }
        }
    }
}
