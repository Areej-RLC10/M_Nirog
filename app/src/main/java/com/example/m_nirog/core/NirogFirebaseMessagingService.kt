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
        val title = message.notification?.title ?: message.data["title"] ?: "M-Nirog Update"
        val body = message.notification?.body ?: message.data["body"] ?: "You have a new update."
        val category = message.data["category"] ?: "GENERAL"
        val bookingId = message.data["bookingId"]
        val notificationId = message.data["notificationId"]
        NirogNotifications.showNotification(
            context = applicationContext,
            title = title,
            body = body,
            category = category,
            bookingId = bookingId,
            notificationId = notificationId,
        )
    }
}
