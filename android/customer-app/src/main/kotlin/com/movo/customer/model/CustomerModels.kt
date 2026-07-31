package com.movo.customer.model

import org.json.JSONObject

data class Coordinate(val latitude: Double, val longitude: Double) {
    val isFinite: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class CustomerProfile(val id: String, val name: String, val phone: String, val email: String? = null, val role: String = "")
data class Quote(val price: Double, val distanceKm: Double, val etaMinutes: Int)
data class NearbyRider(
    val id: String, val name: String, val rating: Double, val distanceKm: Double,
    val location: Coordinate, val locationUpdatedAt: String?, val vehicleMake: String?,
    val vehicleModel: String?, val vehiclePlate: String?, val vehicleColor: String?
) { val vehicle: String get() = listOfNotNull(vehicleColor, vehicleMake, vehicleModel, vehiclePlate).filter(String::isNotBlank).joinToString(" • ").ifBlank { "Motorcycle" } }
data class RiderSummary(
    val id: String, val name: String, val phone: String?, val rating: Double?,
    val vehicleMake: String?, val vehicleModel: String?, val vehiclePlate: String?, val vehicleColor: String?
) { val vehicle: String get() = listOfNotNull(vehicleColor, vehicleMake, vehicleModel, vehiclePlate).filter(String::isNotBlank).joinToString(" • ").ifBlank { "Motorcycle" } }
data class Delivery(
    val id: String, val customerId: String?, val status: String, val pickupAddress: String,
    val destinationAddress: String, val senderName: String?, val destinationName: String?,
    val destinationPhone: String?, val pickup: Coordinate?, val destination: Coordinate?,
    val rider: RiderSummary? = null, val createdAt: String? = null, val updatedAt: String? = null,
    val pickupOtp: String? = null, val deliveryOtp: String? = null, val dispatchMode: String? = null,
    val relationship: String? = null, val orderNo: String? = null, val riderId: String? = null,
    val serviceType: String? = null, val totalCharge: Double? = null, val distanceKm: Double? = null,
    val paymentMethod: String? = null, val paymentStatus: String? = null, val itemDescription: String? = null,
    val podReference: String? = null, val serviceMode: String? = null, val passengerCount: Int? = null
) {
    /** Which product this job is. Rows from before the split carry only a type. */
    val mode: com.movo.design.MovoServiceMode
        get() = serviceMode?.let { com.movo.design.MovoServiceMode.from(it) }
            ?: com.movo.design.MovoServiceMode.forServiceType(serviceType)
}
data class TrackingSnapshot(val delivery: Delivery, val riderLocation: Coordinate?, val riderLocationUpdatedAt: String?, val serverTime: String?)
data class CustomerHome(val active: List<Delivery>, val recent: List<Delivery>, val serverTime: String?)
data class TimelineEvent(val status: String, val note: String?, val createdAt: String?)
data class SendDraft(
    val pickup: Coordinate? = null, val destination: Coordinate? = null,
    val pickupAddress: String = "", val destinationAddress: String = "",
    val senderName: String = "", val senderPhone: String = "",
    val receiverName: String = "", val receiverPhone: String = "",
    val itemType: String = "parcel", val itemDescription: String = "",
    val deliveryInstructions: String = "", val paymentMethod: String = "cash"
)

/**
 * A ride request. Deliberately not a [SendDraft] with fields blanked out: a
 * passenger books their own trip, so there is no sender, no receiver and no item
 * — and a form that asks for them anyway is the difference between a product
 * that was designed and one that was retrofitted.
 */
data class RideDraft(
    val pickup: Coordinate? = null, val destination: Coordinate? = null,
    val pickupAddress: String = "", val destinationAddress: String = "",
    val passengerName: String = "", val passengerPhone: String = "",
    val passengerCount: Int = 1, val hasLuggage: Boolean = false,
    val notes: String = "", val paymentMethod: String = "cash"
) {
    val isRoutePlaced: Boolean get() = pickup?.isFinite == true && destination?.isFinite == true
    /** Everything the fare quote and the booking both need before either is allowed. */
    val isComplete: Boolean
        get() = isRoutePlaced && pickupAddress.isNotBlank() && destinationAddress.isNotBlank() &&
            passengerName.isNotBlank() && passengerPhone.isNotBlank()
}

data class RideJourney(
    val draft: RideDraft, val quote: Quote?, val rideId: String?,
    val creationIdempotencyKey: String, val replacementIdempotencyKey: String
)
data class SendJourney(
    val draft: SendDraft, val quote: Quote?, val deliveryId: String?,
    val creationIdempotencyKey: String, val replacementIdempotencyKey: String
)

internal fun JSONObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name -> opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank) }
internal fun JSONObject.double(vararg names: String): Double? = names.firstNotNullOfOrNull { name -> opt(name)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) }

fun JSONObject.toProfile(): CustomerProfile = CustomerProfile(optString("id"), string("name", "full_name") ?: "Customer", string("phone") ?: "", string("email"), string("role") ?: "")
private fun JSONObject.vehiclePart(vararg names: String) = string(*names)
fun JSONObject.toRider(): RiderSummary = RiderSummary(
    optString("id").ifBlank { optString("user_id") }, string("name", "full_name") ?: "Rider", string("phone"), double("rating", "avg_rating"),
    vehiclePart("vehicle_make", "motorcycle_make", "make"), vehiclePart("vehicle_model", "motorcycle_model", "model", "vehicle_type"),
    vehiclePart("vehicle_plate", "motorcycle_plate", "plate_number"), vehiclePart("vehicle_color", "motorcycle_color", "color")
)
fun JSONObject.toNearbyRider(): NearbyRider = NearbyRider(
    optString("id").ifBlank { optString("user_id") }, string("name", "full_name") ?: "Rider", double("rating", "avg_rating") ?: 0.0,
    double("distance_km", "distanceKm") ?: 0.0, Coordinate(double("latitude", "lat", "current_lat") ?: 0.0, double("longitude", "lng", "current_lng") ?: 0.0),
    string("location_updated_at", "locationUpdatedAt", "last_location_update"), vehiclePart("vehicle_make", "motorcycle_make", "make"),
    vehiclePart("vehicle_model", "motorcycle_model", "model", "vehicle_type"), vehiclePart("vehicle_plate", "motorcycle_plate", "plate_number"),
    vehiclePart("vehicle_color", "motorcycle_color", "color")
)
fun JSONObject.toDelivery(): Delivery {
    val riderJson = optJSONObject("rider")
    val pickup = double("pickup_latitude", "pickup_lat")?.let { lat -> double("pickup_longitude", "pickup_lng")?.let { Coordinate(lat, it) } }
    val destination = double("dest_latitude", "destination_latitude", "dest_lat")?.let { lat -> double("dest_longitude", "destination_longitude", "dest_lng")?.let { Coordinate(lat, it) } }
    return Delivery(
        optString("id"), string("customer_id"), string("status") ?: "pending", string("pickup_address") ?: "Pickup",
        string("dest_address", "destination_address") ?: "Destination", string("pickup_name", "sender_name", "customer_name"),
        string("dest_name", "destination_name"), string("dest_phone", "destination_phone"), pickup, destination, riderJson?.toRider(),
        string("created_at"), string("updated_at"), string("pickup_otp"), string("delivery_otp"), string("dispatch_mode"),
        string("relationship", "participant_role"), string("order_no", "orderNo"), string("rider_id"),
        string("service_type", "serviceType"), double("total_charge", "customer_price", "totalCharge"),
        double("distance_km", "distanceKm"), string("payment_method"), string("payment_status"),
        string("item_description"), string("pod_reference"),
        string("service_mode", "serviceMode"),
        opt("passenger_count")?.toString()?.toIntOrNull()
    )
}
