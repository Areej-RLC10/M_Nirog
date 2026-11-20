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
        NirogNotifications.ensureChannels(this)
        handleNotificationIntent(intent)
        enableEdgeToEdge()
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
        if (intent == null) return
        val bookingId = intent.getStringExtra("bookingId")
        val category = intent.getStringExtra("category")
        if (!bookingId.isNullOrBlank() || !category.isNullOrBlank()) {
            NirogNotifications.emitDeepLink(bookingId, category)
            intent.removeExtra("bookingId")
            intent.removeExtra("category")
        }
    }
}
