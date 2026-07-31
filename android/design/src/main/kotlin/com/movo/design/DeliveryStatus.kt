package com.movo.design

/**
 * The delivery lifecycle from the MOVO platform spec (§6.8 / §10), expressed once
 * so the customer app, the rider app and any future surface show the same words,
 * the same order and the same colour for a given backend status.
 */
enum class MovoDeliveryStage(
    val apiValue: String,
    val customerLabel: String,
    val riderLabel: String,
    val shortLabel: String,
    val step: Int,
    val tone: MovoTone
) {
    Scheduled("scheduled", "Scheduled", "Scheduled", "Scheduled", 0, MovoTone.Neutral),
    Created("created", "Request created", "New request", "Created", 0, MovoTone.Neutral),
    Searching("searching", "Finding a rider", "Offer sent", "Matching", 1, MovoTone.Info),
    AwaitingSelection("awaiting_rider_selection", "Choose another rider", "Awaiting selection", "Reselect", 1, MovoTone.Warning),
    Assigned("assigned", "Rider assigned", "Accepted", "Assigned", 2, MovoTone.Info),
    GoingPickup("going_pickup", "Rider heading to pickup", "Heading to pickup", "En route", 3, MovoTone.Info),
    ArrivedPickup("arrived_pickup", "Rider at pickup", "At pickup", "At pickup", 4, MovoTone.Warning),
    PickedUp("picked_up", "Item collected", "Item collected", "Collected", 5, MovoTone.Info),
    InTransit("in_transit", "On the way", "In transit", "In transit", 6, MovoTone.Info),
    ArrivedDest("arrived_dest", "Rider at destination", "At destination", "Arrived", 7, MovoTone.Warning),
    Delivered("delivered", "Delivered", "Completed", "Delivered", 8, MovoTone.Positive),
    Cancelled("cancelled", "Cancelled", "Cancelled", "Cancelled", 8, MovoTone.Critical),
    Failed("failed", "Not completed", "Not completed", "Failed", 8, MovoTone.Critical);

    val isTerminal: Boolean get() = this == Delivered || this == Cancelled || this == Failed
    val isActive: Boolean get() = !isTerminal

    companion object {
        /** Unknown values from a newer backend degrade to "Created" rather than crashing. */
        fun from(value: String?): MovoDeliveryStage =
            entries.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Created

        /** The seven visible checkpoints a customer follows on the tracking screen. */
        val trackedSteps: List<String> = listOf(
            "Requested", "Matched", "Assigned", "To pickup", "Collected", "In transit", "Delivered"
        )
    }

    /** Maps the fine-grained status onto the seven-point customer progress bar. */
    val trackedStep: Int
        get() = when (this) {
            Scheduled, Created -> 0
            Searching, AwaitingSelection -> 1
            Assigned -> 2
            GoingPickup, ArrivedPickup -> 3
            PickedUp -> 4
            InTransit, ArrivedDest -> 5
            Delivered -> 6
            Cancelled, Failed -> 6
        }
}

/** Human phrasing for a service type, used in headings and receipts. */
fun serviceLabel(serviceType: String?): String = when (serviceType?.lowercase()) {
    "document" -> "Document"
    else -> "Parcel"
}

/** Formats Rwandan francs the way the portals and receipts do: "12,500 RWF". */
fun formatRwf(amount: Number?): String {
    val value = amount?.toDouble() ?: return "—"
    val rounded = kotlin.math.round(value).toLong()
    val digits = rounded.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$digits RWF"
}

/** Distances read naturally: metres below a kilometre, one decimal above. */
fun formatDistance(km: Double?): String {
    if (km == null || km.isNaN()) return "—"
    return if (km < 1) "${kotlin.math.round(km * 1000).toInt()} m" else String.format("%.1f km", km)
}

fun formatMinutes(minutes: Number?): String {
    val total = minutes?.toInt() ?: return "—"
    if (total < 60) return "$total min"
    val hours = total / 60
    val rest = total % 60
    return if (rest == 0) "${hours} h" else "${hours} h ${rest} min"
}
