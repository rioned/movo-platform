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

/**
 * Renders a platform timestamp in the device's local time.
 *
 * The API returns two shapes — SQLite UTC ("2026-07-31 06:08:59", no zone) and ISO
 * strings that already carry one — and a rider should never be shown either raw.
 */
fun formatTimestamp(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return "—"
    val instant = runCatching {
        val normalised = raw.replace(' ', 'T')
        if (normalised.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(normalised)) {
            java.time.Instant.parse(normalised.replace(Regex("([+-]\\d{2}):?(\\d{2})$"), "$1:$2"))
        } else {
            java.time.LocalDateTime.parse(normalised).toInstant(java.time.ZoneOffset.UTC)
        }
    }.getOrNull() ?: return raw

    val local = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    val today = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).toLocalDate()
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(local)
    return when (local.toLocalDate()) {
        today -> "Today $time"
        today.minusDays(1) -> "Yesterday $time"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(local)
    }
}

/** "1 delivery" / "3 deliveries" — counts read as sentences, not as data. */
fun plural(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"

fun formatMinutes(minutes: Number?): String {
    val total = minutes?.toInt() ?: return "—"
    if (total < 60) return "$total min"
    val hours = total / 60
    val rest = total % 60
    return if (rest == 0) "${hours} h" else "${hours} h ${rest} min"
}

/** Generic money formatting for any currency code — "1,250 MZN", "12,500 RWF". */
fun formatMoney(amount: Number?, currency: String): String {
    val value = amount?.toDouble() ?: return "—"
    val rounded = kotlin.math.round(value).toLong()
    val digits = rounded.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$digits $currency"
}

/**
 * The Yango-style ride-hailing trip lifecycle: request, driver match, pickup,
 * the trip itself, and settlement — kept alongside [MovoDeliveryStage] so both
 * apps describe a passenger trip with the same words and colour everywhere.
 */
enum class MovoRideStage(
    val apiValue: String,
    val customerLabel: String,
    val driverLabel: String,
    val shortLabel: String,
    val step: Int,
    val tone: MovoTone
) {
    Searching("searching", "Finding your driver", "Ride requested", "Matching", 0, MovoTone.Info),
    Assigned("assigned", "Driver assigned", "Assigned", "Assigned", 1, MovoTone.Info),
    DriverEnRoute("driver_en_route", "Driver on the way", "Heading to pickup", "En route", 2, MovoTone.Info),
    ArrivedPickup("arrived_pickup", "Your driver has arrived", "At pickup", "Arrived", 3, MovoTone.Warning),
    InProgress("in_progress", "Trip in progress", "Trip started", "On trip", 4, MovoTone.Info),
    ArrivedDestination("arrived_destination", "You've arrived", "At destination", "Arrived", 5, MovoTone.Warning),
    Completed("completed", "Trip completed", "Completed", "Completed", 6, MovoTone.Positive),
    Cancelled("cancelled", "Ride cancelled", "Cancelled", "Cancelled", 6, MovoTone.Critical);

    val isTerminal: Boolean get() = this == Completed || this == Cancelled

    companion object {
        fun from(value: String?): MovoRideStage =
            entries.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Searching
        val trackedSteps: List<String> = listOf("Requested", "Assigned", "To pickup", "Arrived", "On trip", "Completed")
    }

    val trackedStep: Int
        get() = when (this) {
            Searching -> 0
            Assigned -> 1
            DriverEnRoute -> 2
            ArrivedPickup -> 3
            InProgress -> 4
            ArrivedDestination, Completed -> 5
            Cancelled -> 5
        }
}
