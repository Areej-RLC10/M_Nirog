package com.example.m_nirog.ui.screens

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.models.CheckupPackage
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel

@Composable
fun PackagesScreen(vm: BookingViewModel, onSelected: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.loadPackages() }

    NirogScaffold("Choose a checkup") {
        ErrorText(s.error)
        if (s.busy && s.packages.isEmpty()) CircularProgressIndicator()
        s.packages.forEach { pkg ->
            PackageCard(pkg) {
                vm.selectPackage(pkg)
                onSelected()
            }
        }
    }
}

@Composable
private fun PackageCard(pkg: CheckupPackage, onSelect: () -> Unit) {
    InfoCard {
        Text(pkg.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (pkg.isActive) {
            Text("${pkg.description} · ${pkg.priceLabel}")
            Text("Includes: [TESTS FROM PACKAGE LIST]")
            PrimaryButton("Select", onSelect)
        } else {
            Text("Coming soon", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
