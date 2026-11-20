package com.example.m_nirog.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.m_nirog.R
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.viewmodels.AuthViewModel

@Composable
fun ConsentScreen(vm: AuthViewModel, onAccepted: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    NirogScaffold(stringResource(R.string.consent_title)) {
        Text("M-Nirog collects and stores your contact details, address, and lab results only to deliver home blood collection, doctor review, and your health record.")
        Row(
            modifier = Modifier.fillMaxWidth().clickable { agreed = !agreed }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text(stringResource(R.string.consent_checkbox))
        }
        PrimaryButton(stringResource(R.string.continue_btn), onClick = { vm.acceptConsent(); onAccepted() }, enabled = agreed)
    }
}
