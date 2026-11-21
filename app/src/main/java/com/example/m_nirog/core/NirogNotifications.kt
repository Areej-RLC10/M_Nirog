package com.example.m_nirog.core

import android.Manifest
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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class NotificationDeepLink(
    val bookingId: String?,
    val category: String?,
    val notificationId: String? = null,
)

object NirogNotifications {
    const val CHANNEL_UPDATES = "mnirog_updates"
    const val CHANNEL_URGENT = "mnirog_urgent"
    const val EXTRA_BOOKING_ID = "nirog_booking_id"
    const val EXTRA_CATEGORY = "nirog_category"
    const val EXTRA_NOTIFICATION_ID = "nirog_notification_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingDeepLink = MutableStateFlow<NotificationDeepLink?>(null)
    val pendingDeepLink: StateFlow<NotificationDeepLink?> = _pendingDeepLink.asStateFlow()

    fun emitDeepLink(bookingId: String?, category: String?, notificationId: String? = null) {
        if (bookingId.isNullOrBlank() && category.isNullOrBlank() && notificationId.isNullOrBlank()) return
        _pendingDeepLink.value = NotificationDeepLink(
            bookingId = bookingId?.takeIf { it.isNotBlank() },
            category = category?.takeIf { it.isNotBlank() },
            notificationId = notificationId?.takeIf { it.isNotBlank() },
        )
    }

    fun consumeDeepLink() {
        _pendingDeepLink.value = null
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Booking & Lab Updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Payment confirmations, sample pickup updates, and reports ready"
        }
        val urgent = NotificationChannel(
            CHANNEL_URGENT,
            "Urgent Results & Recollection",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Critical health alerts, flagged results, and sample recollection"
        }
        nm.createNotificationChannel(updates)
        nm.createNotificationChannel(urgent)
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        category: String = "GENERAL",
        bookingId: String? = null,
        notificationId: String? = null,
    ) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val isUrgent = category == "URGENT_RESULT" || category == "RECOLLECTION" || category == "NO_SHOW" || category == "CORRECTION_REQUIRED"
        val channelId = if (isUrgent) CHANNEL_URGENT else CHANNEL_UPDATES

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            bookingId?.let { putExtra(EXTRA_BOOKING_ID, it) }
            notificationId?.let { putExtra(EXTRA_NOTIFICATION_ID, it) }
            putExtra(EXTRA_CATEGORY, category)
        }
        val reqCode = ((notificationId ?: "") + ":" + (bookingId ?: "") + ":" + category + ":" + title).hashCode()
        val pi = PendingIntent.getActivity(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(reqCode, notif)
        }
    }

    @Suppress("DEPRECATION")
    fun syncTokenIfLoggedIn(context: Context) {
        val store = SessionStore(context.applicationContext)
        if (!store.isLoggedIn()) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    uploadToken(context.applicationContext, token)
                }
            }
        }
    }

    fun uploadToken(context: Context, token: String) {
        val store = SessionStore(context.applicationContext)
        if (!store.isLoggedIn() || token.isBlank()) return
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
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }
}
