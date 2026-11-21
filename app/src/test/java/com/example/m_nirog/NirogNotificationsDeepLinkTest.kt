package com.example.m_nirog

import com.example.m_nirog.core.NirogNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NirogNotificationsDeepLinkTest {
    @Test
    fun emitsAndConsumesDeepLinkWithNotificationId() {
        NirogNotifications.consumeDeepLink()
        NirogNotifications.emitDeepLink(bookingId = "  ", category = null, notificationId = "")
        assertNull(NirogNotifications.pendingDeepLink.value)

        NirogNotifications.emitDeepLink(
            bookingId = "b-42",
            category = "URGENT_RESULT",
            notificationId = "n-99",
        )
        val link = NirogNotifications.pendingDeepLink.value
        assertEquals("b-42", link?.bookingId)
        assertEquals("URGENT_RESULT", link?.category)
        assertEquals("n-99", link?.notificationId)

        NirogNotifications.consumeDeepLink()
        assertNull(NirogNotifications.pendingDeepLink.value)
    }
}
