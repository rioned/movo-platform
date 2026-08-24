package com.movo.customer.model

import org.json.JSONObject

data class RideType(
    val id: String, val key: String, val name: String, val description: String?,
    val capacity: Int, val fare: Double?, val distanceKm: Double?, val estimatedMinutes: Int?, val currency: String
)

data class RideDriver(
    val id: String?, val name: String, val phone: String?, val rating: Double?,
    val vehicleType: String?, val carMake: String?, val carModel: String?, val carColor: String?, val plate: String?
) {
    val vehicle: String get() = listOfNotNull(carColor, carMake, carModel).filter(String::isNotBlank).joinToString(" • ")
        .ifBlank { if (vehicleType == "motorcycle") "Motorcycle" else "Car" }
}

data class Ride(
    val id: String, val rideNo: String, val status: String,
    val pickupAddress: String, val pickup: Coordinate?, val destAddress: String, val destination: Coordinate?,
    val distanceKm: Double?, val estimatedMinutes: Int?, val totalFare: Double?, val currency: String,
    val paymentMethod: String?, val paymentStatus: String?, val cancellationFee: Double?,
    val shareToken: String?, val customerRating: Int?, val driverId: String?
)

data class RideTimelineEvent(val status: String, val note: String?, val createdAt: String?)

internal fun JSONObject.rideString(vararg names: String): String? = names.firstNotNullOfOrNull { name -> opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank) }
internal fun JSONObject.rideDouble(vararg names: String): Double? = names.firstNotNullOfOrNull { name -> opt(name)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) }
internal fun JSONObject.rideInt(vararg names: String): Int? = names.firstNotNullOfOrNull { name -> opt(name)?.toString()?.toDoubleOrNull()?.toInt() }

fun JSONObject.toRideType(): RideType = RideType(
    optString("ride_type_id").ifBlank { optString("id") }, optString("key"), optString("name"),
    rideString("description"), optInt("capacity", 4), rideDouble("fare"), rideDouble("distance_km"),
    rideInt("estimated_minutes"), optString("currency", "MZN")
)

fun JSONObject.toRideDriver(): RideDriver = RideDriver(
    rideString("id", "user_id"), rideString("full_name", "name") ?: "Driver", rideString("phone"),
    rideDouble("avg_rating", "rating"), rideString("vehicle_type"), rideString("car_make"),
    rideString("car_model"), rideString("car_color"), rideString("car_plate", "motorcycle_plate", "plate")
)

fun JSONObject.toRide(): Ride {
    val pickup = rideDouble("pickup_lat")?.let { lat -> rideDouble("pickup_lng")?.let { Coordinate(lat, it) } }
    val destination = rideDouble("dest_lat")?.let { lat -> rideDouble("dest_lng")?.let { Coordinate(lat, it) } }
    return Ride(
        optString("id"), rideString("ride_no") ?: "", rideString("status") ?: "searching",
        rideString("pickup_address") ?: "Pickup", pickup, rideString("dest_address") ?: "Destination", destination,
        rideDouble("distance_km"), rideInt("estimated_minutes"), rideDouble("total_fare"), rideString("currency") ?: "MZN",
        rideString("payment_method"), rideString("payment_status"), rideDouble("cancellation_fee"),
        rideString("share_token"), rideInt("customer_rating"), rideString("driver_id")
    )
}
