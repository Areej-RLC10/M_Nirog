package com.example.m_nirog.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.m_nirog.R
import com.example.m_nirog.core.DevConfig
import com.example.m_nirog.models.PickerJobStatus
import com.example.m_nirog.ui.components.BarcodeScannerDialog
import com.example.m_nirog.ui.components.ErrorText
import com.example.m_nirog.ui.components.InfoCard
import com.example.m_nirog.ui.components.NirogScaffold
import com.example.m_nirog.ui.components.PrimaryButton
import com.example.m_nirog.ui.components.SecondaryButton
import com.example.m_nirog.ui.components.SummaryRow
import com.example.m_nirog.ui.viewmodels.PickerViewModel
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PickerPickupsScreen(
    vm: PickerViewModel,
    isStaffSession: Boolean = false,
    onOpenPickup: (String) -> Unit,
    onBackToPatient: () -> Unit,
    onSignOut: () -> Unit = {},
) {
    val s by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    NirogScaffold(
        stringResource(R.string.picker_pickups_title),
        onBack = if (isStaffSession) null else onBackToPatient,
    ) {
        if (!s.liveMode && DevConfig.ENABLED) {
            InfoCard {
                Text("Dev preview mode", fontWeight = FontWeight.SemiBold)
                Text("Sign out and log in with +977 9800000002 for live PostgreSQL picker jobs.")
            }
        }
        ErrorText(s.error)
        if (!s.message.isNullOrBlank()) {
            Text(s.message!!, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        if (s.pickups.isEmpty()) {
            InfoCard {
                Text("No assigned pickups yet.")
            }
        } else {
            s.pickups.forEach { job ->
                Box(modifier = Modifier.fillMaxWidth().clickable { vm.clearFeedback(); onOpenPickup(job.id) }) {
                    InfoCard {
                        SummaryRow(job.patientName, job.slotLabel)
                        SummaryRow(job.packageName, job.city.label)
                        Text(job.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val statusLine = if (!job.noShowPhotoUri.isNullOrBlank()) {
                            "${job.status.label} · Photo proof saved"
                        } else {
                            job.status.label
                        }
                        Text(statusLine, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (s.liveMode && DevConfig.ENABLED) {
            PrimaryButton("Dev: Pull paid bookings", onClick = vm::assignPaidBookings, loading = s.busy)
        }
        if (isStaffSession) {
            SecondaryButton("Sign out", onClick = onSignOut)
        } else {
            SecondaryButton(stringResource(R.string.picker_back_to_patient), onClick = onBackToPatient)
        }
    }
}

@Composable
fun PickerPickupDetailScreen(
    pickupId: String,
    vm: PickerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    val job = s.find(pickupId) ?: return

    var barcodeInput by remember(job.id) { mutableStateOf(job.sampleCode.orEmpty()) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showFullPhoto by remember { mutableStateOf(false) }
    var pendingPhotoUri by rememberSaveable(job.id) { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val photoSource = job.noShowPhotoUri
    val previewBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = job.id, key2 = photoSource) {
        value = if (photoSource.isNullOrBlank()) {
            null
        } else if (photoSource == "server-uploaded") {
            val bytes = vm.fetchNoShowPhotoBytes(job.id)
            if (bytes != null && bytes.isNotEmpty()) {
                withContext(Dispatchers.IO) { decodeRotatedBitmap(bytes)?.asImageBitmap() }
            } else {
                null
            }
        } else {
            withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(Uri.parse(photoSource))?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) decodeRotatedBitmap(bytes)?.asImageBitmap() else null
            }
        }
    }

    val arrivedAt = job.arrivedAtMillis
    val waitRemainingSec = if (arrivedAt == null) {
        15 * 60L
    } else {
        (((arrivedAt + 15 * 60 * 1000L) - now) / 1000L).coerceAtLeast(0L)
    }
    val waitCompleted = arrivedAt != null && waitRemainingSec == 0L

    LaunchedEffect(arrivedAt) {
        if (arrivedAt != null) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    val photoProofLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingPhotoUri
        if (success && !uri.isNullOrBlank()) {
            vm.attachNoShowPhoto(job.id, uri)
        } else {
            Toast.makeText(
                context,
                "Photo not captured. Tap the checkmark in Camera after taking the photo.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun launchDoorwayCamera() {
        runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "doorway_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            pendingPhotoUri = uri.toString()
            photoProofLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(context, "Camera unavailable.", Toast.LENGTH_SHORT).show()
        }
    }

    val barcodePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showBarcodeScanner = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan barcodes.", Toast.LENGTH_SHORT).show()
        }
    }

    val photoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchDoorwayCamera()
        } else {
            Toast.makeText(context, "Camera permission is required for doorway photo proof.", Toast.LENGTH_SHORT).show()
        }
    }

    NirogScaffold(stringResource(R.string.picker_pickup_detail_title), onBack = onBack) {
        InfoCard {
            SummaryRow("Patient", job.patientName)
            SummaryRow("Package", job.packageName)
            SummaryRow("Slot", "${job.dateLabel} · ${job.slotLabel}")
            SummaryRow("Address", "${job.address}, ${job.city.label}")
            SummaryRow("Status", job.status.label)
        }

        SecondaryButton(
            stringResource(R.string.picker_call_patient),
            onClick = {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${job.patientPhone}"))
                runCatching { context.startActivity(intent) }
            },
        )

        if (job.status == PickerJobStatus.ASSIGNED) {
            PrimaryButton(
                stringResource(R.string.picker_arrived),
                onClick = { vm.markArrived(job.id) },
                loading = s.busy,
            )
        }

        if (job.status == PickerJobStatus.ASSIGNED || job.status == PickerJobStatus.ARRIVED) {
            InfoCard {
                Text("1. Sample collection", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = barcodeInput,
                    onValueChange = { barcodeInput = it },
                    label = { Text(stringResource(R.string.picker_sample_code_label)) },
                    placeholder = { Text("e.g. MNR-482190") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    stringResource(R.string.picker_scan_barcode),
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            showBarcodeScanner = true
                        } else {
                            barcodePermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                PrimaryButton(
                    stringResource(R.string.picker_confirm_collected),
                    onClick = { vm.collectSample(job.id, barcodeInput) },
                    loading = s.busy,
                )
            }

            InfoCard {
                Text("2. Patient no-show (15-min wait + photo)", fontWeight = FontWeight.SemiBold)
                if (arrivedAt == null) {
                    Text("Tap 'I have arrived' at the address to start the 15-minute wait timer.")
                } else if (!waitCompleted) {
                    Text(
                        stringResource(
                            R.string.picker_wait_remaining,
                            (waitRemainingSec / 60).toInt(),
                            (waitRemainingSec % 60).toInt(),
                        ),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        stringResource(R.string.picker_wait_done),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (DevConfig.ENABLED && !waitCompleted) {
                    SecondaryButton(
                        "Dev: Complete 15-min wait",
                        onClick = { vm.devCompleteWait(job.id) },
                    )
                }
                SecondaryButton(
                    if (photoSource.isNullOrBlank()) {
                        stringResource(R.string.picker_take_photo_proof)
                    } else {
                        "Retake doorway photo"
                    },
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            launchDoorwayCamera()
                        } else {
                            photoPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                if (!photoSource.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.picker_photo_attached),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    DoorwayPhotoThumbnail(
                        bitmap = previewBitmap,
                        onOpenFull = { showFullPhoto = true },
                    )
                }
                SecondaryButton(
                    stringResource(R.string.picker_mark_no_show),
                    onClick = { vm.markNoShow(job.id) },
                    enabled = waitCompleted && !photoSource.isNullOrBlank() && !s.busy,
                )
            }
        } else if (!photoSource.isNullOrBlank()) {
            InfoCard {
                Text("Doorway photo proof", fontWeight = FontWeight.SemiBold)
                DoorwayPhotoThumbnail(
                    bitmap = previewBitmap,
                    onOpenFull = { showFullPhoto = true },
                )
            }
        }

        if (!s.message.isNullOrBlank()) {
            Text(s.message!!, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        ErrorText(s.error)
    }

    if (showBarcodeScanner) {
        BarcodeScannerDialog(
            onScanned = { code ->
                barcodeInput = code
                showBarcodeScanner = false
                Toast.makeText(context, "Scanned barcode: $code", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showBarcodeScanner = false },
        )
    }

    if (showFullPhoto && previewBitmap != null) {
        Dialog(onDismissRequest = { showFullPhoto = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Doorway photo proof", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showFullPhoto = false }) {
                            Text("Close")
                        }
                    }
                    Image(
                        bitmap = previewBitmap!!,
                        contentDescription = "Doorway photo proof",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DoorwayPhotoThumbnail(
    bitmap: ImageBitmap?,
    onOpenFull: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    if (bitmap == null) {
        Text(
            "Loading photo preview...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Image(
                bitmap = bitmap,
                contentDescription = "Doorway photo thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickable(onClick = onOpenFull),
            )
            Text(
                "Tap thumbnail to view full photo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun decodeRotatedBitmap(bytes: ByteArray, maxDim: Int = 1280): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
        sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val orientation = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return decoded
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated != decoded) decoded.recycle()
    return rotated
}
