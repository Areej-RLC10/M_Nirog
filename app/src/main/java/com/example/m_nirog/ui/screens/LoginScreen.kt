package com.example.m_nirog.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.viewmodels.AuthViewModel

@Composable
fun LoginScreen(vm: AuthViewModel, onLoggedIn: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.loggedIn) { if (s.loggedIn) onLoggedIn() }

    NirogScaffold("Log in") {
        OutlinedTextField(
            value = s.phone,
            onValueChange = vm::onPhone,
            label = { Text("Phone number") },
            prefix = { Text("+977 ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!s.otpSent) {
            ErrorText(s.error)
            PrimaryButton("Send OTP", vm::sendOtp)
        } else {
            OutlinedTextField(
                value = s.otp,
                onValueChange = vm::onOtp,
                label = { Text("OTP code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (DevConfig.ENABLED) Text("Dev mode: any 6 digits works.")
            ErrorText(s.error)
            PrimaryButton("Verify and continue", vm::verify)
        }
    }
}
