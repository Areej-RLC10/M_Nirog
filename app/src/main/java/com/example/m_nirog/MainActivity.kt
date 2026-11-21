package com.example.m_nirog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.m_nirog.core.NirogNotifications
import com.example.m_nirog.navigation.AppNav
import com.example.m_nirog.ui.design.NirogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NirogNotifications.ensureChannels(this)
        handleNotificationIntent(intent)
        setContent {
            NirogTheme { AppNav() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val bookingId = extras.getString(NirogNotifications.EXTRA_BOOKING_ID)
            ?: extras.getString("bookingId")
        val category = extras.getString(NirogNotifications.EXTRA_CATEGORY)
            ?: extras.getString("category")
        val notificationId = extras.getString(NirogNotifications.EXTRA_NOTIFICATION_ID)
            ?: extras.getString("notificationId")
        if (!bookingId.isNullOrBlank() || !category.isNullOrBlank() || !notificationId.isNullOrBlank()) {
            NirogNotifications.emitDeepLink(
                bookingId = bookingId,
                category = category,
                notificationId = notificationId,
            )
            intent.removeExtra(NirogNotifications.EXTRA_BOOKING_ID)
            intent.removeExtra(NirogNotifications.EXTRA_CATEGORY)
            intent.removeExtra(NirogNotifications.EXTRA_NOTIFICATION_ID)
            intent.removeExtra("bookingId")
            intent.removeExtra("category")
            intent.removeExtra("notificationId")
        }
    }
}
