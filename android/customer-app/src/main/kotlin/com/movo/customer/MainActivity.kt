package com.movo.customer

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.movo.customer.parcel.ParcelApp
import com.movo.customer.parcel.ui.ParcelTheme
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject

/** Customer launcher: objects travel by moto; passengers are never booked here. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var orderId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readOrderIntent(intent)
        setContent { ParcelTheme { ParcelApp(initialOrderId = orderId) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readOrderIntent(intent) }
    private fun readOrderIntent(intent: Intent) { orderId = intent.getStringExtra("order_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } }
}

// Kept for older API consumers while the rider/shared modules remain unchanged.
internal fun JSONObject.dataObject(): JSONObject = optJSONObject("data") ?: this