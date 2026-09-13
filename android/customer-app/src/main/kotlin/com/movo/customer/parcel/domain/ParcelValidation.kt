package com.movo.customer.parcel.domain

internal fun validateDraft(draft: ParcelDraft) {
    val points = listOfNotNull(draft.pickup, draft.destination) + draft.extraStops
    require(draft.pickup != null && draft.destination != null) { "Choose pickup and destination" }
    require(points.all { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.address.isNotBlank() }) { "Choose valid addresses and coordinates" }
    require(draft.serviceType in setOf("parcel", "document")) { "Unsupported service type" }
    require(draft.cashOnDelivery.isFinite() && draft.cashOnDelivery >= 0) { "Invalid cash on delivery amount" }
}
internal fun validateContacts(draft: ParcelDraft) {
    require(draft.senderName.isNotBlank() && draft.recipientName.isNotBlank()) { "Sender and recipient names required" }
    require(listOf(draft.senderPhone, draft.recipientPhone).all { it.matches(Regex("\\+?[0-9]{9,15}")) }) { "Valid contact phone numbers required" }
}
