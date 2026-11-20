package com.example.m_nirog.ui.screens

import android.net.Uri
import androidx.compose.material3.Text
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
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.ReportViewModel

@Composable
fun ReportAndAdviceScreen(
    bookingId: String,
    vm: ReportViewModel,
    onBack: () -> Unit,
) {
    SecureScreenEffect()

    val context = LocalContext.current
    val s by vm.state.collectAsState()
    val report = s.report
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    LaunchedEffect(bookingId) { vm.load(bookingId) }

    NirogScaffold("Your report", onBack = onBack) {
        ErrorText(s.error)
        InfoCard {
            report?.values.orEmpty().forEach { v ->
                SummaryRow(v.name, "${v.value} ${v.unit} (${v.flag.label})")
            }
            Text("Flags: Low, Normal, High, or Critical")
        }
        InfoCard {
            Text("Doctor's suggestions", fontWeight = FontWeight.SemiBold)
            Text(report?.doctorNote ?: "Doctor review in progress.")
            if (!report?.doctorName.isNullOrBlank()) {
                Text("Dr. ${report?.doctorName} · NMC ${report?.doctorNmcNumber.orEmpty()}")
            }
        }
        Text("This is not a substitute for emergency care.", fontWeight = FontWeight.SemiBold)
        PrimaryButton(
            "Download & view PDF",
            onClick = {
                val uri = previewUri ?: PdfHelper.downloadAndGetUri(context, report, showToast = true)
                if (uri != null) {
                    previewUri = uri
                    showPreview = true
                }
            },
        )
        if (previewUri != null) {
            SecondaryButton(
                "Open in external PDF app",
                onClick = { previewUri?.let { PdfHelper.openContentUri(context, it, "application/pdf") } },
            )
        }
    }

    val activeUri = previewUri
    if (showPreview && activeUri != null) {
        PdfPreviewDialog(
            uri = activeUri,
            title = "${report?.packageName ?: "Lab"} Report PDF",
            onDismiss = { showPreview = false },
        )
    }
}
