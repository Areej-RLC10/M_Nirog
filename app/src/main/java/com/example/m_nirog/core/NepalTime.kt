package com.example.m_nirog.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NepalTime {
    private val nepalZone: TimeZone = TimeZone.getTimeZone("Asia/Kathmandu")

    fun formatDateTime(utcMillis: Long): String {
        val fmt = SimpleDateFormat("EEE dd MMM, hh:mm a", Locale.US)
        fmt.timeZone = nepalZone
        return "${fmt.format(Date(utcMillis))} NPT"
    }
}
