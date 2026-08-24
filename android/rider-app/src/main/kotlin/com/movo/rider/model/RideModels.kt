package com.movo.rider.model

import org.json.JSONObject

/** A ride offered to this driver — automatic dispatch, same shape as [DeliveryOffer]. */
data class RideOffer(
    val offerId: String,
    val rideId: String,
    val rideNo: String,
    val pickupAddress: String,
    val destinationAddress: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val earnings: Double,
    val distanceKm: Double,
    val estimatedMinutes: Int,
    val expiresAt: String?
)

/** The ride a driver is currently executing — passenger pickup through drop-off. */
data class ActiveRide(
    val id: String,
    val rideNo: String,
    val status: String,
    val pickupAddress: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val destinationAddress: String,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val totalFare: Double,
    val currency: String,
    val paymentMethod: String?,
    val distanceKm: Double,
    val estimatedMinutes: Int
)

private fun JSONObject.stringOrNull(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank) }
private fun JSONObject.doubleOrNull(vararg names: String): Double? =
    names.firstNotNullOfOrNull { name -> opt(name)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) }

fun JSONObject.toRideOffer(): RideOffer = RideOffer(
    offerId = optString("offer_id"),
    rideId = optString("id"),
    rideNo = stringOrNull("ride_no") ?: "",
    pickupAddress = stringOrNull("pickup_address") ?: "Pickup",
    destinationAddress = stringOrNull("dest_address") ?: "Destination",
    pickupLat = doubleOrNull("pickup_lat"), pickupLng = doubleOrNull("pickup_lng"),
    destinationLat = doubleOrNull("dest_lat"), destinationLng = doubleOrNull("dest_lng"),
    earnings = doubleOrNull("driver_earnings", "earnings") ?: 0.0,
    distanceKm = doubleOrNull("distance_km") ?: 0.0,
    estimatedMinutes = optInt("estimated_minutes", 0),
    expiresAt = stringOrNull("expires_at")
)

fun JSONObject.toActiveRide(): ActiveRide = ActiveRide(
    id = optString("id"),
    rideNo = stringOrNull("ride_no") ?: "",
    status = stringOrNull("status") ?: "assigned",
    pickupAddress = stringOrNull("pickup_address") ?: "Pickup",
    pickupLat = doubleOrNull("pickup_lat"), pickupLng = doubleOrNull("pickup_lng"),
    destinationAddress = stringOrNull("dest_address") ?: "Destination",
    destinationLat = doubleOrNull("dest_lat"), destinationLng = doubleOrNull("dest_lng"),
    totalFare = doubleOrNull("total_fare") ?: 0.0,
    currency = stringOrNull("currency") ?: "MZN",
    paymentMethod = stringOrNull("payment_method"),
    distanceKm = doubleOrNull("distance_km") ?: 0.0,
    estimatedMinutes = optInt("estimated_minutes", 0)
)

/** The driver workflow: which action is next and which endpoint drives it. No
 * verification code is needed — the passenger verifies the plate visually. */
data class RideAction(val label: String, val path: String)

fun nextRideAction(status: String): RideAction? = when (status) {
    "assigned" -> RideAction("Head to pickup", "en-route")
    "driver_en_route" -> RideAction("I have arrived", "arrive-pickup")
    "arrived_pickup" -> RideAction("Start trip", "start")
    "in_progress" -> RideAction("I have arrived at destination", "arrive-destination")
    "arrived_destination" -> RideAction("Complete trip", "complete")
    else -> null
}
