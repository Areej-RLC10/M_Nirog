package com.example.m_nirog.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.m_nirog.R
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.core.NepalTime
import com.example.m_nirog.core.NirogNotifications
import com.example.m_nirog.core.PdfHelper
import com.example.m_nirog.core.PdfPreviewDialog
import com.example.m_nirog.models.HealthRecord
import com.example.m_nirog.models.RecordType
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.ProfileViewModel
import java.io.File

@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    onOpenHealthRecords: () -> Unit,
    onOpenPickerMode: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val s by vm.state.collectAsState()
    var showAddAddress by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var cityInput by remember { mutableStateOf("Kathmandu") }
    var streetInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    NirogScaffold(stringResource(R.string.profile_title)) {
        if (s.profile.phone.isNotBlank()) {
            Text(
                "Signed in: ${s.profile.phone}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = s.profile.fullName,
            onValueChange = vm::onName,
            label = { Text("Full name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.profile.dob,
            onValueChange = vm::onDob,
            label = { Text("Date of birth") },
            placeholder = { Text("DD / MM / YYYY") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.profile.gender,
            onValueChange = vm::onGender,
            label = { Text("Gender") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(stringResource(R.string.save_profile), vm::saveProfile, loading = s.busy)
        if (!s.statusMessage.isNullOrBlank()) {
            Text(s.statusMessage!!, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }

        Text("Saved address", fontWeight = FontWeight.SemiBold)
        InfoCard {
            val savedAddr = s.profile.addresses.lastOrNull()
            if (savedAddr == null) {
                Text("No saved address yet.")
            } else {
                Text("${savedAddr.street}, ${savedAddr.city.label}")
            }
            Text("Kathmandu or Pokhara only")
        }
        ErrorText(s.error)
        SecondaryButton(
            if (s.profile.addresses.isEmpty()) "Add address" else "Change address",
            onClick = { showAddAddress = true },
        )
        SecondaryButton(
            if (s.unreadNotificationsCount > 0) "Notifications (${s.unreadNotificationsCount} unread)" else "Notifications",
            onClick = {
                vm.loadNotifications()
                showNotifications = true
            },
        )
        SecondaryButton(stringResource(R.string.health_records), onClick = onOpenHealthRecords)
        if (DevConfig.ENABLED) {
            SecondaryButton(stringResource(R.string.open_picker_mode), onClick = onOpenPickerMode)
            SecondaryButton("Dev: Send test FCM push", onClick = vm::sendTestPush)
            if (!s.pushTestMessage.isNullOrBlank()) {
                Text(
                    s.pushTestMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SecondaryButton("Sign out", onClick = onSignOut)
    }

    if (showAddAddress) {
        AlertDialog(
            onDismissRequest = { showAddAddress = false },
            title = { Text(if (s.profile.addresses.isEmpty()) "Add address" else "Change address") },
            text = {
                Column {
                    OutlinedTextField(
                        value = cityInput,
                        onValueChange = { cityInput = it },
                        label = { Text("City (Kathmandu or Pokhara)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = streetInput,
                        onValueChange = { streetInput = it },
                        label = { Text("Street, area, landmark") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addAddress(cityInput, streetInput)
                        streetInput = ""
                        showAddAddress = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddAddress = false }) { Text("Cancel") }
            },
        )
    }

    if (showNotifications) {
        AlertDialog(
            onDismissRequest = { showNotifications = false },
            title = { Text("Notifications") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (s.notifications.isEmpty()) {
                        Text("No notifications yet.")
                    } else {
                        s.notifications.forEachIndexed { idx, item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!item.isRead) vm.markNotificationRead(item.id)
                                        if (!item.bookingId.isNullOrBlank()) {
                                            showNotifications = false
                                            NirogNotifications.emitDeepLink(item.bookingId, item.category)
                                        }
                                    },
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (!item.isRead) "NEW: ${item.title}" else item.title,
                                    fontWeight = if (!item.isRead) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (item.category == "URGENT_RESULT") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(item.body, style = MaterialTheme.typography.bodyMedium)
                                val meta = buildList {
                                    if (item.createdAtMs > 0L) add(NepalTime.formatDateTime(item.createdAtMs))
                                    if (!item.bookingId.isNullOrBlank()) add("Tap to open booking")
                                }.joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (idx < s.notifications.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (s.unreadNotificationsCount > 0) {
                    TextButton(onClick = vm::markAllNotificationsRead) {
                        Text("Mark all read")
                    }
                } else {
                    TextButton(onClick = { showNotifications = false }) { Text("Close") }
                }
            },
            dismissButton = {
                if (s.unreadNotificationsCount > 0) {
                    TextButton(onClick = { showNotifications = false }) { Text("Close") }
                }
            },
        )
    }
}

@Composable
fun HealthRecordsScreen(
    vm: ProfileViewModel,
    onBack: () -> Unit,
    onOpenRecord: (HealthRecord) -> Unit,
    onAddRecord: () -> Unit,
) {
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    var previewPdfUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    NirogScaffold(stringResource(R.string.health_records), onBack = onBack) {
        ErrorText(s.error)
        if (s.records.isEmpty()) {
            InfoCard {
                Text("No health records yet", fontWeight = FontWeight.SemiBold)
                Text("Upload or scan your past lab reports and prescriptions.")
            }
        } else {
            s.records.forEach { rec ->
                InfoCard {
                    SummaryRow(rec.title, rec.dateLabel)
                    Text("Type: ${rec.type.label}")
                    SecondaryButton(
                        "Open",
                        onClick = {
                            onOpenRecord(rec)
                            if (rec.fileUrl.isNullOrBlank()) {
                                Toast.makeText(context, "No file attached to this record.", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.openRecordFile(rec) { uri, mime ->
                                    val opened = PdfHelper.openContentUri(context, uri, mime)
                                    if (!opened && mime.contains("pdf", ignoreCase = true)) {
                                        previewPdfUri = uri
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        PrimaryButton("Add record", onAddRecord)
    }

    val activeUri = previewPdfUri
    if (activeUri != null) {
        PdfPreviewDialog(
            uri = activeUri,
            title = "Health Record PDF",
            onDismiss = { previewPdfUri = null },
        )
    }
}

@Composable
fun AddRecordScreen(
    vm: ProfileViewModel,
    onBack: () -> Unit,
    onScanWithCamera: () -> Unit,
    onChooseFile: () -> Unit,
) {
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RecordType.LAB_REPORT) }
    var date by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            var name = "Selected document"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx) ?: name
                }
            }
            vm.onFilePicked(name, uri.toString())
        }
    }

    var pendingScanUri by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingScanUri
        if (success && !uri.isNullOrBlank()) {
            vm.onFilePicked("Camera scan.jpg", uri)
        } else {
            Toast.makeText(context, "Camera scan cancelled. Tap the checkmark in Camera to save.", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCameraScan() {
        runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "record_scan_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingScanUri = uri.toString()
            cameraLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(context, "Camera unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCameraScan()
        } else {
            Toast.makeText(context, "Camera permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(s.saved) {
        if (s.saved) {
            vm.consumeSaved()
            onBack()
        }
    }

    NirogScaffold("Add record", onBack = onBack) {
        PrimaryButton(
            "Scan with camera",
            onClick = {
                onScanWithCamera()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    launchCameraScan()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        )
        SecondaryButton(
            "Choose a file",
            onClick = {
                onChooseFile()
                filePicker.launch(arrayOf("application/pdf", "image/*"))
            },
        )
        if (!s.selectedFileName.isNullOrBlank()) {
            Text("Selected: ${s.selectedFileName}", fontWeight = FontWeight.Medium)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            placeholder = { Text("e.g. Blood test") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordType.entries.forEach { option ->
                FilterChip(
                    selected = type == option,
                    onClick = { type = option },
                    label = { Text(option.label) },
                )
            }
        }
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("Date") },
            placeholder = { Text("DD / MM / YYYY") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(s.error)
        PrimaryButton("Save record", onClick = { vm.saveRecord(title, type, date) }, loading = s.busy)
    }
}
