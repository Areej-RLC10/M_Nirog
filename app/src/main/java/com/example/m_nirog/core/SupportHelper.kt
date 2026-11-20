package com.example.m_nirog.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object SupportHelper {
    private const val SUPPORT_PHONE = "+9779826115288"

    fun contactSupport(context: Context) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE"))
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "Call support at $SUPPORT_PHONE", Toast.LENGTH_LONG).show()
        }
    }
}
