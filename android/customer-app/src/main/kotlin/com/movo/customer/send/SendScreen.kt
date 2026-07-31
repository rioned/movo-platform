package com.movo.customer.send

import androidx.compose.runtime.Composable
import com.movo.customer.model.CustomerProfile
import com.movo.customer.network.CustomerApi
import com.movo.customer.session.CustomerSession

@Composable
fun SendScreen(api: CustomerApi, profile: CustomerProfile, session: CustomerSession, online: Boolean, onTracking: (String) -> Unit) {
    MapFirstSendScreen(api, profile, session, online, onTracking)
}
