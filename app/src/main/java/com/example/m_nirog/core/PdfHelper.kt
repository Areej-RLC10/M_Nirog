package com.example.m_nirog.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.m_nirog.models.ReportDetail
import java.io.File
import java.io.FileOutputStream

object PdfHelper {

    fun openReportPdf(context: Context, report: ReportDetail?): Boolean {
        val url = report?.pdfUrl
        if (!url.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            return runCatching { context.startActivity(intent); true }.getOrElse {
                Toast.makeText(context, "No app available to open PDF link.", Toast.LENGTH_SHORT).show()
                false
            }
        }
        val uri = saveSamplePdf(context, report) ?: return false
        return openContentUri(context, uri, "application/pdf")
    }

    fun downloadReportPdf(context: Context, report: ReportDetail?) {
        downloadAndGetUri(context, report, showToast = true)
    }

    fun downloadAndGetUri(
        context: Context,
        report: ReportDetail?,
        showToast: Boolean = true,
    ): Uri? {
        val url = report?.pdfUrl
        if (!url.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "Could not open download link.", Toast.LENGTH_SHORT).show()
            }
            return null
        }
        val uri = saveSamplePdf(context, report)
        if (showToast) {
            val msg = if (uri != null) {
                "Report PDF saved to Downloads"
            } else {
                "Could not save report PDF"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
        return uri
    }

    fun openContentUri(context: Context, uri: Uri, mimeType: String = "*/*"): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent); true }.getOrElse {
            Toast.makeText(context, "No external viewer found; showing inside app.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun saveScanImage(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "MNirog_Scan_${System.currentTimeMillis()}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MNirog")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }
                uri
            } else {
                val file = File(context.cacheDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveDownloadedFile(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                if (mimeType.startsWith("image/", ignoreCase = true)) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MNirog")
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { resolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
                    uri
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { resolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
                    uri
                }
            } else {
                val file = File(context.cacheDir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun renderPdfPages(context: Context, uri: Uri): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return pages

        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                try {
                    val bmp = Bitmap.createBitmap(
                        (page.width * 2).coerceAtLeast(1),
                        (page.height * 2).coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages.add(bmp)
                } finally {
                    page.close()
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd.close() }
        }
        return pages
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        paint: Paint,
    ): Float {
        var y = startY
        var line = ""
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                canvas.drawText(line, x, y, paint)
                y += lineHeight
                line = word
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
        return y
    }

    private fun saveSamplePdf(context: Context, report: ReportDetail?): Uri? {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f }
        val boldPaint = Paint().apply { textSize = 12f; isFakeBoldText = true }

        var y = 60f
        canvas.drawText("M-Nirog Lab Report", 48f, y, titlePaint)
        y += 28f
        canvas.drawText("Package: ${report?.packageName ?: "Basic Health"} · ${report?.dateLabel ?: ""}", 48f, y, bodyPaint)
        y += 28f
        canvas.drawText("Test Results", 48f, y, boldPaint)
        y += 20f

        report?.values.orEmpty().forEach { v ->
            y = drawWrapped(
                canvas,
                "${v.name}: ${v.value} ${v.unit} (${v.flag.label}) [Ref ${v.referenceLow}-${v.referenceHigh}]",
                48f,
                y,
                500f,
                20f,
                bodyPaint,
            )
        }

        y += 14f
        if (!report?.doctorNote.isNullOrBlank()) {
            canvas.drawText("Doctor's Suggestions:", 48f, y, boldPaint)
            y += 18f
            y = drawWrapped(canvas, report?.doctorNote.orEmpty(), 48f, y, 500f, 18f, bodyPaint)
            y += 4f
            canvas.drawText("Dr. ${report?.doctorName.orEmpty()} · NMC ${report?.doctorNmcNumber.orEmpty()}", 48f, y, boldPaint)
            y += 28f
        }
        canvas.drawText("This is not a substitute for emergency care.", 48f, y, boldPaint)
        doc.finishPage(page)

        val fileName = "MNirog_Report_${report?.bookingId ?: "sample"}.pdf"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out -> doc.writeTo(out) }
                }
                uri
            } else {
                val file = File(context.cacheDir, fileName)
                FileOutputStream(file).use { out -> doc.writeTo(out) }
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        } finally {
            doc.close()
        }
    }
}

@Composable
fun PdfPreviewDialog(
    uri: Uri,
    title: String = "Lab Report PDF",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pages = remember(uri) { PdfHelper.renderPdfPages(context, uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pages.isEmpty()) {
                    Text("Saved to Downloads. Use 'Open externally' to view in another app.")
                } else {
                    pages.forEachIndexed { idx, bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "PDF page ${idx + 1}",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = { PdfHelper.openContentUri(context, uri, "application/pdf") }) {
                Text("Open externally")
            }
        },
    )
}
