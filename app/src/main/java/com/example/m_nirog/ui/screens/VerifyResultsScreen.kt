package com.example.m_nirog.ui.screens

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.example.m_nirog.core.PdfHelper
import com.example.m_nirog.core.PdfPreviewDialog
import com.example.m_nirog.core.SecureScreenEffect
import com.example.m_nirog.models.LabValue
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.ReportViewModel

@Composable
fun VerifyResultsScreen(
    bookingId: String,
    vm: ReportViewModel,
    onBack: () -> Unit,
    onDone: (confirmed: Boolean) -> Unit,
) {
    SecureScreenEffect()

    val context = LocalContext.current
    val s by vm.state.collectAsState()
    var flagging by remember { mutableStateOf<LabValue?>(null) }
    var note by remember { mutableStateOf("") }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var showPdfDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookingId) { vm.load(bookingId) }
    LaunchedEffect(s.verifiedDone, s.flaggedDone) {
        if (s.verifiedDone) {
            vm.consumeDone()
            onDone(true)
        } else if (s.flaggedDone) {
            vm.consumeDone()
            onDone(false)
        }
    }

    NirogScaffold("Check your results", onBack = onBack) {
        Text("Compare each value with your lab PDF.")
        s.report?.values.orEmpty().forEach { v ->
            InfoCard {
                SummaryRow(v.name, "${v.value} ${v.unit}")
                Text("Reference ${v.referenceLow} to ${v.referenceHigh}")
                if (v.flaggedByPatient && !v.patientNote.isNullOrBlank()) {
                    Text("Flagged: ${v.patientNote}", fontWeight = FontWeight.Medium)
                }
                SecondaryButton(
                    text = if (v.flaggedByPatient) "Update flag" else "Flag this value",
                    onClick = { note = v.patientNote.orEmpty(); flagging = v },
                )
            }
        }
        ErrorText(s.error)
        SecondaryButton(
            "Open lab PDF",
            onClick = {
                val uri = previewUri ?: PdfHelper.downloadAndGetUri(context, s.report, showToast = false)
                if (uri != null) {
                    previewUri = uri
                    showPdfDialog = true
                } else {
                    PdfHelper.openReportPdf(context, s.report)
                }
            },
        )
        PrimaryButton("Confirm all values", onClick = vm::confirmAll, loading = s.busy)
        Text("If you do nothing, results go to the doctor after 72 hours.")
    }

    flagging?.let { item ->
        AlertDialog(
            onDismissRequest = { flagging = null },
            title = { Text("Flag ${item.name}") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What looks wrong?") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.flagValue(item.id, note)
                        flagging = null
                    },
                ) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = { flagging = null }) { Text("Cancel") }
            },
        )
    }

    val activeUri = previewUri
    if (showPdfDialog && activeUri != null) {
        PdfPreviewDialog(
            uri = activeUri,
            title = "Lab PDF",
            onDismiss = { showPdfDialog = false },
        )
    }
}
