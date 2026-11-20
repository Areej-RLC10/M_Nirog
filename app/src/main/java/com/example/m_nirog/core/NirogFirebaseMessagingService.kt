package com.example.m_nirog.core

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class NirogFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        NirogNotifications.uploadToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "M-Nirog update"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Open M-Nirog to view details."
        val category = message.data["category"]
        val bookingId = message.data["bookingId"]
        NirogNotifications.showNotification(
            context = applicationContext,
            title = title,
            body = body,
            category = category,
            bookingId = bookingId,
        )
    }
}
