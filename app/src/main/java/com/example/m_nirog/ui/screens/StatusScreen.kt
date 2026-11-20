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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.m_nirog.R
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.core.SupportHelper
import com.example.m_nirog.data.AppError
import com.example.m_nirog.data.friendlyMessage
import com.example.m_nirog.models.Booking
import com.example.m_nirog.models.BookingStatus
import com.example.m_nirog.models.TimelineSteps
import com.example.m_nirog.models.isFinal
import com.example.m_nirog.models.timelineAnchor
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.KhaltiWebViewDialog
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.viewmodels.BookingViewModel
import kotlinx.coroutines.delay

private data class StatusNotice(val title: String, val message: String)

private fun Booking.notice(): StatusNotice? = when (status) {
    BookingStatus.CANCELLED -> StatusNotice(
        "This booking was cancelled",
        if (!recollectionReason.isNullOrBlank()) recollectionReason
        else "If you had paid, your refund has been started.",
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
        if (!recollectionReason.isNullOrBlank()) {
            "Reason: $recollectionReason. A new pickup is free of charge. We will confirm a new time shortly."
        } else {
            "The lab could not use your sample. A new pickup is free of charge. We will confirm a new time shortly."
        },
    )
    BookingStatus.CORRECTION_REQUIRED -> StatusNotice(
        "Correction in progress",
        "You flagged a value. The lab is checking it and we will ask you to verify again.",
    )
    BookingStatus.COMPLETED -> if (isUrgent) {
        StatusNotice(
            "Urgent lab result",
            "A critical value was flagged in your report. Please open your report right away.",
        )
    } else null
    BookingStatus.UNKNOWN -> StatusNotice(
        "Updating",
        "We are updating your booking status. Please check again in a moment.",
    )
    else -> null
}

@Composable
fun StatusScreen(
    vm: BookingViewModel,
    onVerifyingPayment: () -> Unit = {},
    onVerifyResults: (String) -> Unit = {},
    onViewReport: (String) -> Unit = {},
    onNewSampleNeeded: () -> Unit = {},
    onUrgentResult: (String) -> Unit = {},
    onBookAgain: () -> Unit,
) {
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    val booking = s.booking ?: return
    var showConfirm by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val status = booking.status
    val notice = booking.notice()
    val holdLeft = ((booking.holdExpiresAtMillis - now) / 1000).coerceAtLeast(0L)
    val holdOver = status == BookingStatus.PENDING_PAYMENT && holdLeft == 0L

    LaunchedEffect(booking.id, status) {
        if (status == BookingStatus.PENDING_PAYMENT) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    LaunchedEffect(holdOver) {
        if (holdOver) vm.expire()
    }

    val khaltiSession = s.khaltiSession
    if (khaltiSession != null) {
        KhaltiWebViewDialog(
            session = khaltiSession,
            onCallback = { pidx, statusStr ->
                vm.onKhaltiRedirect(pidx, statusStr)
                onVerifyingPayment()
            },
            onDismiss = vm::dismissKhaltiCheckout,
        )
    }

    NirogScaffold("${booking.packageName} · ${booking.dateLabel}") {
        if (status == BookingStatus.PENDING_PAYMENT) {
            InfoCard {
                Text(
                    stringResource(R.string.slot_held_for, (holdLeft / 60).toInt(), (holdLeft % 60).toInt()),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.slot_hold_hint))
            }
            PrimaryButton(
                "Pay with Khalti",
                onClick = vm::payWithKhalti,
                enabled = holdLeft > 0,
                loading = s.busy,
            )
        }
        if (notice != null) {
            InfoCard {
                Text(notice.title, fontWeight = FontWeight.SemiBold)
                Text(notice.message)
            }
        }
        when {
            status == BookingStatus.COMPLETED -> {
                Timeline(booking)
                PrimaryButton(stringResource(R.string.view_report), onClick = { onViewReport(booking.id) })
                SecondaryButton(stringResource(R.string.book_another), onClick = { vm.reset(); onBookAgain() })
            }
            status.isFinal -> {
                PrimaryButton(
                    if (status == BookingStatus.PATIENT_NO_SHOW) stringResource(R.string.book_again)
                    else stringResource(R.string.book_another),
                    onClick = { vm.reset(); onBookAgain() },
                )
                if (status == BookingStatus.PATIENT_NO_SHOW) {
                    SecondaryButton(
                        stringResource(R.string.contact_support),
                        onClick = { SupportHelper.contactSupport(context) },
                    )
                }
            }
            else -> {
                Timeline(booking)
                if (status == BookingStatus.AWAITING_VERIFICATION) {
                    PrimaryButton(stringResource(R.string.verify_results), onClick = { onVerifyResults(booking.id) })
                }
                if (status == BookingStatus.RECOLLECTION_REQUIRED) {
                    PrimaryButton(stringResource(R.string.view_recollection_details), onClick = onNewSampleNeeded)
                }
                CancelSection(status, onCancel = { showConfirm = true })
            }
        }
        ErrorText(s.error)
        if (DevConfig.ENABLED) {
            Text("Dev tools", style = MaterialTheme.typography.labelMedium)
            SecondaryButton("Dev: advance status", vm::devAdvance)
            SecondaryButton(
                "Dev: Urgent result",
                onClick = {
                    vm.devUrgent()
                    onUrgentResult(booking.id)
                },
            )
            SecondaryButton(
                "Dev: Pending payment (15:00 hold)",
                onClick = { vm.devSet(BookingStatus.PENDING_PAYMENT) },
            )
            listOf(
                "Awaiting verification" to BookingStatus.AWAITING_VERIFICATION,
                "Completed" to BookingStatus.COMPLETED,
                "New sample needed" to BookingStatus.RECOLLECTION_REQUIRED,
                "Correction in progress" to BookingStatus.CORRECTION_REQUIRED,
                "Pickup missed" to BookingStatus.PATIENT_NO_SHOW,
                "Expired" to BookingStatus.EXPIRED,
            ).forEach { (label, st) ->
                SecondaryButton(
                    "Dev: $label",
                    onClick = {
                        vm.devSet(st)
                        if (st == BookingStatus.RECOLLECTION_REQUIRED) {
                            onNewSampleNeeded()
                        }
                    },
                )
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
