package com.example.m_nirog.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.m_nirog.models.KhaltiCheckoutSession

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KhaltiWebViewDialog(
    session: KhaltiCheckoutSession,
    onCallback: (pidx: String?, status: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember(session.pidx) { mutableStateOf(true) }
    var handled by remember(session.pidx) { mutableStateOf(false) }

    val webView = remember(session.pidx) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                private fun tryHandleReturnUrl(url: String?): Boolean {
                    if (url.isNullOrBlank()) return false
                    if (!url.startsWith(session.returnUrl)) return false
                    if (!handled) {
                        handled = true
                        val uri = Uri.parse(url)
                        onCallback(
                            uri.getQueryParameter("pidx") ?: session.pidx,
                            uri.getQueryParameter("status"),
                        )
                    }
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return tryHandleReturnUrl(request?.url?.toString())
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    if (tryHandleReturnUrl(url)) {
                        view?.stopLoading()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                }
            }
            loadUrl(session.paymentUrl)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Khalti Sandbox Checkout",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}
