package com.example.m_nirog.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.m_nirog.MainActivity
import com.example.m_nirog.R
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class NotificationDeepLink(
    val bookingId: String?,
    val category: String?,
)

object NirogNotifications {
    const val CHANNEL_UPDATES = "mnirog_updates"
    const val CHANNEL_URGENT = "mnirog_urgent"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pendingDeepLink = MutableStateFlow<NotificationDeepLink?>(null)
    val pendingDeepLink: StateFlow<NotificationDeepLink?> = _pendingDeepLink.asStateFlow()

    fun emitDeepLink(bookingId: String?, category: String?) {
        val cleanBooking = bookingId?.takeIf { it.isNotBlank() }
        val cleanCategory = category?.takeIf { it.isNotBlank() }
        if (cleanBooking != null || cleanCategory != null) {
            _pendingDeepLink.value = NotificationDeepLink(cleanBooking, cleanCategory)
        }
    }

    fun consumeDeepLink() {
        _pendingDeepLink.value = null
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Booking & Report Updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Booking status updates, sample pickup alerts, and lab report notifications."
        }
        val urgent = NotificationChannel(
            CHANNEL_URGENT,
            "Urgent Lab Results",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Critical lab result alerts requiring immediate attention."
            enableVibration(true)
            enableLights(true)
        }
        nm.createNotificationChannel(updates)
        nm.createNotificationChannel(urgent)
    }

    @SuppressLint("MissingPermission")
    fun showNotification(
        context: Context,
        title: String,
        body: String,
        category: String?,
        bookingId: String?,
    ) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!bookingId.isNullOrBlank()) putExtra("bookingId", bookingId)
            if (!category.isNullOrBlank()) putExtra("category", category)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() and 0xFFFF).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channelId = if (category == "URGENT_RESULT") CHANNEL_URGENT else CHANNEL_UPDATES
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(
                (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                notification,
            )
        }
    }

    @Suppress("DEPRECATION")
    fun syncTokenIfLoggedIn(context: Context) {
        val store = SessionStore(context)
        if (!store.isLoggedIn() || store.accessToken().isNullOrBlank()) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    uploadToken(context, token)
                }
            }
        }
    }

    fun uploadToken(context: Context, token: String) {
        val store = SessionStore(context)
        if (!store.isLoggedIn() || store.accessToken().isNullOrBlank()) return
        scope.launch {
            runCatching {
                ApiClient(store).post("/me/fcm-token", JSONObject().put("token", token))
            }
        }
    }

    fun clearTokenForAccessToken(accessToken: String?) {
        if (accessToken.isNullOrBlank()) return
        scope.launch {
            runCatching {
                val conn = (URL("${ApiConfig.BASE_URL}/me/fcm-token/clear").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                try {
                    conn.outputStream.use { it.write("{}".toByteArray()) }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}
