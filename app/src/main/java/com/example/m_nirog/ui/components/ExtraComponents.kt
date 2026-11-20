package com.example.m_nirog.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SlotJustFilledCard() {
    InfoCard {
        Text("That slot just filled", fontWeight = FontWeight.SemiBold)
        Text("Someone booked the last place. Please pick another time.")
    }
}

@Composable
fun CancelNotAllowedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cannot cancel", fontWeight = FontWeight.SemiBold) },
        text = {
            Text("A picker has been assigned, so this booking can no longer be cancelled. Need help? Call support.")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
fun NoConnectionCard(
    lastStatusLabel: String,
    lastUpdated: String,
    onRetry: () -> Unit,
) {
    InfoCard {
        Text("No internet connection", fontWeight = FontWeight.SemiBold)
        Text("Showing the last status we saved: $lastStatusLabel. It may be out of date.")
        Text("Last updated $lastUpdated", style = MaterialTheme.typography.bodySmall)
        PrimaryButton("Try again", onRetry)
    }
}
