package com.movo.customer.parcel.data

/** Deterministic timing only for the explicitly selected demo repository. */
fun demoStatus(elapsedSeconds: Long): String = when {
    elapsedSeconds < 10 -> "searching"
    elapsedSeconds < 25 -> "assigned"
    elapsedSeconds < 40 -> "picked_up"
    elapsedSeconds < 70 -> "in_transit"
    else -> "delivered"
}
