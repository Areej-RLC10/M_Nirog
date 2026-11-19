package com.example.m_nirog.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.data.AppError
import com.example.m_nirog.data.friendlyMessage
import com.example.m_nirog.models.Booking
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.TimelineSteps
import com.example.m_nirog.models.isFinal
import com.example.m_nirog.models.timelineAnchor
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel

private data class StatusNotice(val title: String, val message: String)

private fun BookingStatus.notice(): StatusNotice? = when (this) {
    BookingStatus.CANCELLED -> StatusNotice(
        "This booking was cancelled",
        "If you had paid, your refund has been started.",
    )
    BookingStatus.EXPIRED -> StatusNotice(
        "Your booking expired",
        "The payment was not completed in time, so the slot was released. You were not charged.",
    )
    BookingStatus.PATIENT_NO_SHOW -> StatusNotice(
        "Pickup missed",
        "The picker waited 15 minutes at your address but could not collect the sample. " +
            "This booking is closed and cannot be refunded.",
    )
    BookingStatus.RECOLLECTION_REQUIRED -> StatusNotice(
        "New sample needed",
        "The lab could not use your sample. A new pickup is free of charge. We will confirm a new time shortly.",
    )
    BookingStatus.CORRECTION_REQUIRED -> StatusNotice(
        "Correction in progress",
        "You flagged a value. The lab is checking it and we will ask you to verify again.",
    )
    BookingStatus.UNKNOWN -> StatusNotice(
        "Updating",
        "We are updating your booking status. Please check again in a moment.",
    )
    else -> null
}

@Composable
fun StatusScreen(vm: BookingViewModel, onBookAgain: () -> Unit) {
    val s by vm.state.collectAsState()
    val booking = s.booking ?: return
    var showConfirm by remember { mutableStateOf(false) }
    val status = booking.status
    val notice = status.notice()

    NirogScaffold("${booking.packageName} · ${booking.dateLabel}") {
        if (notice != null) {
            InfoCard {
                Text(notice.title, fontWeight = FontWeight.SemiBold)
                Text(notice.message)
            }
        }
        if (status.isFinal) {
            PrimaryButton(
                if (status == BookingStatus.PATIENT_NO_SHOW) "Book again" else "Book another checkup",
                onClick = { vm.reset(); onBookAgain() },
            )
        } else {
            Timeline(booking)
            CancelSection(status, onCancel = { showConfirm = true })
        }
        ErrorText(s.error)
        if (DevConfig.ENABLED) {
            Text("Dev tools", style = MaterialTheme.typography.labelMedium)
            SecondaryButton("Dev: advance status", vm::devAdvance)
            listOf(
                "New sample needed" to BookingStatus.RECOLLECTION_REQUIRED,
                "Correction in progress" to BookingStatus.CORRECTION_REQUIRED,
                "Pickup missed" to BookingStatus.PATIENT_NO_SHOW,
                "Expired" to BookingStatus.EXPIRED,
            ).forEach { (label, st) ->
                SecondaryButton("Dev: $label", { vm.devSet(st) })
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Cancel this booking?") },
            text = { Text("Your slot will be released. If you paid, a full refund is started.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; vm.cancel() }) { Text("Yes, cancel") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Keep booking") }
            },
        )
    }
}

@Composable
private fun CancelSection(status: BookingStatus, onCancel: () -> Unit) {
    when (status) {
        BookingStatus.PENDING_PAYMENT, BookingStatus.PAID -> {
            SecondaryButton("Cancel booking", onCancel)
            Text("Cancelling is possible until a picker is assigned.")
        }
        BookingStatus.PICKER_ASSIGNED, BookingStatus.SAMPLE_COLLECTED, BookingStatus.AT_LAB -> {
            SecondaryButton("Cancel booking", onCancel, enabled = false)
            Text(AppError("CANCEL_NOT_ALLOWED").friendlyMessage())
        }
        else -> Unit
    }
}

@Composable
private fun Timeline(booking: Booking) {
    val currentIndex = TimelineSteps.indexOf(booking.status.timelineAnchor)
    val allDone = booking.status == BookingStatus.COMPLETED
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TimelineSteps.forEachIndexed { i, step ->
            val state = when {
                allDone -> StepState.Done
                currentIndex < 0 -> StepState.Todo
                i < currentIndex -> StepState.Done
                i == currentIndex -> StepState.Current
                else -> StepState.Todo
            }
            val sub = if (step == BookingStatus.PICKER_ASSIGNED && booking.pickerName != null) {
                "${booking.pickerName} · ${booking.pickerPhone}"
            } else null
            StepRow(step.patientLabel, sub, state)
        }
    }
}

private enum class StepState { Done, Current, Todo }

@Composable
private fun StepRow(label: String, sub: String?, state: StepState) {
    val primary = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val dot = Modifier.size(20.dp)
        when (state) {
            StepState.Done -> Box(dot.background(primary, CircleShape))
            StepState.Current -> Box(dot.background(Color.White, CircleShape).border(BorderStroke(4.dp, primary), CircleShape))
            StepState.Todo -> Box(dot.border(BorderStroke(2.dp, Color(0xFF9CA3AF)), CircleShape))
        }
        Column {
            Text(label, fontWeight = if (state == StepState.Current) FontWeight.SemiBold else FontWeight.Normal)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}
