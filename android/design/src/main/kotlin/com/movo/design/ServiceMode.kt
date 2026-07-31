package com.movo.design

/**
 * The two products MOVO sells, mirrored from the server's SERVICE_MODES table.
 *
 * A ride carries a passenger; a delivery carries something on their behalf. The
 * lifecycle is identical — that is why both are one pipeline — but almost every
 * word around it differs, and a passenger told their "item was collected" is the
 * kind of detail that makes an app feel unfinished. Every mode-dependent string
 * in either app resolves through here, so the two products can never drift.
 */
enum class MovoServiceMode(
    val apiValue: String,
    /** Verb the customer picks from the mode chooser: "Ride" / "Send". */
    val action: String,
    val title: String,
    val tagline: String,
    /** Lowercase noun for mid-sentence use: "your ride", "your delivery". */
    val noun: String,
    val serviceTypes: List<String>,
    val defaultServiceType: String
) {
    Ride(
        apiValue = "ride",
        action = "Ride",
        title = "Ride with MOVO",
        tagline = "Hop on a moto and go",
        noun = "ride",
        serviceTypes = listOf("ride"),
        defaultServiceType = "ride"
    ),
    Delivery(
        apiValue = "delivery",
        action = "Send",
        title = "Send a package",
        tagline = "Parcels and documents across Kigali",
        noun = "delivery",
        serviceTypes = listOf("parcel", "document"),
        defaultServiceType = "parcel"
    );

    val isRide: Boolean get() = this == Ride

    companion object {
        /** Unknown or absent values read as Delivery — the product that predates the split. */
        fun from(value: String?): MovoServiceMode =
            entries.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Delivery

        /** Resolves the mode a service type belongs to, for rows that carry only the type. */
        fun forServiceType(serviceType: String?): MovoServiceMode {
            val normalised = serviceType?.trim()?.lowercase()
            return entries.firstOrNull { mode -> mode.serviceTypes.any { it == normalised } } ?: Delivery
        }
    }
}

/**
 * The customer-facing name of a stage in the given mode.
 *
 * The stage-keyed [MovoDeliveryStage.customerLabel] stays as the delivery wording
 * so existing screens are unaffected; this overload is what mode-aware screens call.
 */
fun MovoDeliveryStage.customerLabel(mode: MovoServiceMode): String {
    if (!mode.isRide) return customerLabel
    return when (this) {
        MovoDeliveryStage.Scheduled -> "Scheduled"
        MovoDeliveryStage.Created -> "Ride requested"
        MovoDeliveryStage.Searching -> "Finding your rider"
        MovoDeliveryStage.AwaitingSelection -> "Choose another rider"
        MovoDeliveryStage.Assigned -> "Rider on the way"
        MovoDeliveryStage.GoingPickup -> "Rider coming to you"
        MovoDeliveryStage.ArrivedPickup -> "Your rider has arrived"
        MovoDeliveryStage.PickedUp -> "On board"
        MovoDeliveryStage.InTransit -> "On your way"
        MovoDeliveryStage.ArrivedDest -> "You have arrived"
        MovoDeliveryStage.Delivered -> "Trip completed"
        MovoDeliveryStage.Cancelled -> "Cancelled"
        MovoDeliveryStage.Failed -> "Not completed"
    }
}

/** The same stage as the rider doing the job sees it. */
fun MovoDeliveryStage.riderLabel(mode: MovoServiceMode): String {
    if (!mode.isRide) return riderLabel
    return when (this) {
        MovoDeliveryStage.Scheduled -> "Scheduled"
        MovoDeliveryStage.Created -> "New ride request"
        MovoDeliveryStage.Searching -> "Ride offer sent"
        MovoDeliveryStage.AwaitingSelection -> "Awaiting selection"
        MovoDeliveryStage.Assigned -> "Ride accepted"
        MovoDeliveryStage.GoingPickup -> "Heading to passenger"
        MovoDeliveryStage.ArrivedPickup -> "Waiting for passenger"
        MovoDeliveryStage.PickedUp -> "Passenger on board"
        MovoDeliveryStage.InTransit -> "Trip in progress"
        MovoDeliveryStage.ArrivedDest -> "Arrived at drop-off"
        MovoDeliveryStage.Delivered -> "Trip completed"
        MovoDeliveryStage.Cancelled -> "Cancelled"
        MovoDeliveryStage.Failed -> "Not completed"
    }
}

/**
 * The seven progress checkpoints, worded for the mode.
 *
 * Kept short: seven columns share the width of one phone, so a long word wraps to
 * two lines and pushes the bar taller than the sheet can spare.
 */
fun trackedSteps(mode: MovoServiceMode): List<String> =
    if (mode.isRide) listOf("Booked", "Matched", "Accepted", "Pickup", "Aboard", "Moving", "Arrived")
    else MovoDeliveryStage.trackedSteps

/** Human phrasing for a service type inside a mode: "Ride", "Parcel", "Document". */
fun serviceLabel(serviceType: String?, mode: MovoServiceMode): String =
    if (mode.isRide) "Moto ride" else serviceLabel(serviceType)

/** What the two map endpoints are called in this mode. */
fun pickupLabel(mode: MovoServiceMode): String = if (mode.isRide) "Pick-up" else "Pickup"
fun destinationLabel(mode: MovoServiceMode): String = if (mode.isRide) "Drop-off" else "Destination"

/**
 * Whether this mode asks the rider for a code at the destination. A passenger
 * arrives with the rider, so there is nothing left to verify — mirrors the
 * server's `requiresDeliveryOtp`.
 */
fun requiresHandoverCode(mode: MovoServiceMode): Boolean = !mode.isRide
