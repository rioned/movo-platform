package com.movo.customer.parcel.account

import java.util.Locale

object AccountRules {
    fun validContact(name: String, phone: String): Boolean = name.trim().isNotEmpty() && name.length <= 100 &&
        Regex("^\\+?[0-9]{7,15}$").matches(phone.replace(" ", "").replace("-", ""))
    fun validAddress(label: String, address: String): Boolean =
        label.trim().isNotEmpty() && label.length <= 40 && address.trim().isNotEmpty() && address.length <= 300

    fun canRate(status: String, rating: Int?): Boolean = status.lowercase(Locale.ROOT) == "delivered" && rating == null
    fun validRating(score: Int, review: String): Boolean = score in 1..5 && review.length <= 1000
    fun validSupport(category: String, subject: String, description: String): Boolean =
        category in setOf("delivery", "payment", "other") && subject.trim().isNotEmpty() &&
            subject.length <= 120 && description.trim().isNotEmpty() && description.length <= 1000
    fun validProfile(name: String, email: String): Boolean = name.trim().isNotEmpty() && name.length <= 100 &&
        (email.isBlank() || (email.length <= 254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())))
    fun matchesOrderFilter(status: String, filter: String): Boolean = when (filter) {
        "completed" -> status.lowercase(Locale.ROOT) == "delivered"
        "active" -> status.lowercase(Locale.ROOT) in setOf("pending", "searching", "assigned", "accepted", "rider_assigned", "picked_up", "in_transit", "arriving", "arrived", "pickup_confirmed", "requested")
        else -> true
    }
}

fun accountText(language: String, en: String, rw: String, fr: String): String = when (language) {
    "rw" -> rw
    "fr" -> fr
    else -> en
}
