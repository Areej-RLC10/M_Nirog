package com.example.m_nirog.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.m_nirog.R
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.viewmodels.AuthViewModel

@Composable
fun LoginScreen(vm: AuthViewModel, onLoggedIn: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.loggedIn) { if (s.loggedIn) onLoggedIn() }

    NirogScaffold(stringResource(R.string.login_title)) {
        OutlinedTextField(
            value = s.phone,
            onValueChange = vm::onPhone,
            label = { Text(stringResource(R.string.phone_number)) },
            prefix = { Text("+977 ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!s.otpSent) {
            Text(
                "Enter any 10-digit number starting with 9. A new number creates a separate patient account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorText(s.error)
            PrimaryButton(stringResource(R.string.send_otp), onClick = vm::sendOtp, enabled = !s.busy)
        } else {
            OutlinedTextField(
                value = s.otp,
                onValueChange = vm::onOtp,
                label = { Text(stringResource(R.string.otp_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (DevConfig.ENABLED) {
                val otpText = s.devOtp?.let { "Dev OTP from server: $it" }
                    ?: stringResource(R.string.dev_otp_hint)
                Text(otpText)
            }
            ErrorText(s.error)
            PrimaryButton(stringResource(R.string.verify_continue), onClick = vm::verify, enabled = !s.busy)
            SecondaryButton("Use a different number", onClick = vm::changePhone, enabled = !s.busy)
        }
    }
}
