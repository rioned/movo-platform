package com.movo.rider.model

import com.movo.design.MovoServiceMode
import com.movo.design.requiresHandoverCode
import org.json.JSONArray
import org.json.JSONObject

/** Rider account state as the platform sees it (spec §7.2, §7.3, §7.9). */
data class RiderProfile(
    val name: String = "Rider",
    val approvalStatus: String = "pending",
    val availability: String = "offline",
    val photoPath: String? = null,
    val totalDeliveries: Int = 0,
    val totalEarnings: Double = 0.0,
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val motorcyclePlate: String = "",
    val motorcycleMake: String = "",
    val motorcycleType: String = "",
    val motorcycleColor: String = "",
    /** Which products this rider works. Dispatch only offers what is enabled. */
    val acceptsRides: Boolean = true,
    val acceptsDeliveries: Boolean = true
) {
    val isApproved: Boolean get() = approvalStatus == "approved"
    val isOnline: Boolean get() = availability == "online" || availability == "busy"
    /** A rider taking neither product would sit online receiving nothing. */
    val hasAnyService: Boolean get() = acceptsRides || acceptsDeliveries
}

/** A delivery offered to this rider, before acceptance reveals contact details. */
data class DeliveryOffer(
    val offerId: String,
    val deliveryId: String,
    val orderNo: String,
    val serviceType: String,
    val pickupAddress: String,
    val destinationAddress: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val earnings: Double,
    val distanceKm: Double,
    val expiresAt: String?,
    val specialInstructions: String?,
    val serviceMode: String = "delivery",
    val passengerCount: Int = 1,
    val hasLuggage: Boolean = false
) {
    val mode: MovoServiceMode get() = MovoServiceMode.from(serviceMode)
}

/** The delivery a rider is currently executing, with the contact details unlocked. */
data class ActiveDelivery(
    val id: String,
    val orderNo: String,
    val status: String,
    val serviceType: String,
    val pickupAddress: String,
    val pickupName: String,
    val pickupPhone: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val destinationAddress: String,
    val destinationName: String,
    val destinationPhone: String,
    val destinationLat: Double?,
    val destinationLng: Double?,
    val earnings: Double,
    val distanceKm: Double,
    val itemDescription: String?,
    val specialInstructions: String?,
    val serviceMode: String = "delivery",
    val passengerCount: Int = 1,
    val hasLuggage: Boolean = false
) {
    val mode: MovoServiceMode get() = MovoServiceMode.from(serviceMode)

    /**
     * True where the workflow requires a verification code before continuing.
     *
     * Both products verify the handover at pickup — a boarding code and a
     * collection code are the same mechanism. Only a delivery verifies again at
     * the destination, because only a delivery is received by someone else.
     */
    val requiresVerification: Boolean
        get() = status == "arrived_pickup" || (status == "arrived_dest" && requiresHandoverCode(mode))
}

data class EarningEntry(
    val orderNo: String, val amount: Double, val completedAt: String?, val route: String,
    val serviceMode: String = "delivery"
) {
    val mode: MovoServiceMode get() = MovoServiceMode.from(serviceMode)
}

/** Earnings for one product, so a rider can see which work actually pays. */
data class ModeEarnings(val count: Int = 0, val total: Double = 0.0)

data class EarningsSummary(
    val period: String = "today",
    val total: Double = 0.0,
    val count: Int = 0,
    val platformFees: Double = 0.0,
    val entries: List<EarningEntry> = emptyList(),
    val rides: ModeEarnings = ModeEarnings(),
    val deliveries: ModeEarnings = ModeEarnings()
) {
    /** Only worth splitting the headline when they actually worked both. */
    val worksBothProducts: Boolean get() = rides.count > 0 && deliveries.count > 0
}

data class PerformanceStats(
    val totalDeliveries: Int = 0,
    val acceptanceRate: Int = 0,
    val cancellationRate: Int = 0,
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val totalEarnings: Double = 0.0
)

/** Snapshot rendered by the rider home screen. */
data class RiderHomeState(
    val profile: RiderProfile = RiderProfile(),
    val offer: DeliveryOffer? = null,
    val activeDelivery: ActiveDelivery? = null,
    val serverTime: String? = null,
    val pendingSync: Int = 0
)

private fun JSONObject.stringOrNull(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank) }

private fun JSONObject.doubleOrNull(vararg names: String): Double? =
    names.firstNotNullOfOrNull { name -> opt(name)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) }

fun JSONObject.toRiderProfile(): RiderProfile = RiderProfile(
    name = stringOrNull("full_name", "name") ?: "Rider",
    approvalStatus = stringOrNull("approval_status") ?: "pending",
    availability = stringOrNull("availability", "online_status") ?: "offline",
    photoPath = stringOrNull("profile_photo_url"),
    totalDeliveries = optInt("total_deliveries", 0),
    totalEarnings = doubleOrNull("total_earnings") ?: 0.0,
    rating = doubleOrNull("avg_rating") ?: 0.0,
    ratingCount = optInt("rating_count", 0),
    motorcyclePlate = stringOrNull("motorcycle_plate").orEmpty(),
    motorcycleMake = stringOrNull("motorcycle_make").orEmpty(),
    motorcycleType = stringOrNull("motorcycle_type").orEmpty(),
    motorcycleColor = stringOrNull("motorcycle_color").orEmpty(),
    acceptsRides = optInt("accepts_rides", 1) == 1,
    acceptsDeliveries = optInt("accepts_deliveries", 1) == 1
)

fun JSONObject.toDeliveryOffer(): DeliveryOffer = DeliveryOffer(
    offerId = optString("offer_id"),
    deliveryId = optString("id"),
    orderNo = stringOrNull("order_no") ?: "",
    serviceType = stringOrNull("service_type") ?: "parcel",
    pickupAddress = stringOrNull("pickup_address") ?: "Pickup",
    destinationAddress = stringOrNull("dest_address") ?: "Destination",
    pickupLat = doubleOrNull("pickup_lat"),
    pickupLng = doubleOrNull("pickup_lng"),
    destinationLat = doubleOrNull("dest_lat"),
    destinationLng = doubleOrNull("dest_lng"),
    earnings = doubleOrNull("rider_earnings", "earnings") ?: 0.0,
    distanceKm = doubleOrNull("distance_km") ?: 0.0,
    expiresAt = stringOrNull("expires_at"),
    specialInstructions = stringOrNull("special_instructions"),
    serviceMode = stringOrNull("service_mode") ?: "delivery",
    passengerCount = optInt("passenger_count", 1).coerceAtLeast(1),
    hasLuggage = optInt("has_luggage", 0) == 1
)

fun JSONObject.toActiveDelivery(): ActiveDelivery = ActiveDelivery(
    id = optString("id"),
    orderNo = stringOrNull("order_no") ?: "",
    status = stringOrNull("status") ?: "assigned",
    serviceType = stringOrNull("service_type") ?: "parcel",
    pickupAddress = stringOrNull("pickup_address") ?: "Pickup",
    pickupName = stringOrNull("pickup_name").orEmpty(),
    pickupPhone = stringOrNull("pickup_phone").orEmpty(),
    pickupLat = doubleOrNull("pickup_lat"),
    pickupLng = doubleOrNull("pickup_lng"),
    destinationAddress = stringOrNull("dest_address") ?: "Destination",
    destinationName = stringOrNull("dest_name").orEmpty(),
    destinationPhone = stringOrNull("dest_phone").orEmpty(),
    destinationLat = doubleOrNull("dest_lat"),
    destinationLng = doubleOrNull("dest_lng"),
    earnings = doubleOrNull("rider_earnings") ?: 0.0,
    distanceKm = doubleOrNull("distance_km") ?: 0.0,
    itemDescription = stringOrNull("item_description"),
    specialInstructions = stringOrNull("special_instructions"),
    serviceMode = stringOrNull("service_mode") ?: "delivery",
    passengerCount = optInt("passenger_count", 1).coerceAtLeast(1),
    hasLuggage = optInt("has_luggage", 0) == 1
)

fun JSONObject.toEarningsSummary(period: String): EarningsSummary {
    val recent: JSONArray? = optJSONArray("recent")
    return EarningsSummary(
        period = period,
        total = doubleOrNull("total_earnings") ?: 0.0,
        count = optInt("count", 0),
        platformFees = doubleOrNull("total_fees") ?: 0.0,
        entries = List(recent?.length() ?: 0) { index ->
            val entry = recent!!.getJSONObject(index)
            EarningEntry(
                orderNo = entry.optString("order_no"),
                amount = entry.optDouble("rider_earnings", 0.0),
                completedAt = entry.stringOrNull("delivered_at"),
                route = "${entry.optString("pickup_address")} → ${entry.optString("dest_address")}",
                serviceMode = entry.stringOrNull("service_mode") ?: "delivery"
            )
        },
        rides = optJSONObject("by_mode")?.optJSONObject("ride").toModeEarnings(),
        deliveries = optJSONObject("by_mode")?.optJSONObject("delivery").toModeEarnings()
    )
}

private fun JSONObject?.toModeEarnings(): ModeEarnings =
    if (this == null) ModeEarnings()
    else ModeEarnings(optInt("count", 0), doubleOrNull("total_earnings") ?: 0.0)

fun JSONObject.toPerformanceStats(): PerformanceStats = PerformanceStats(
    totalDeliveries = optInt("total_deliveries", 0),
    acceptanceRate = optInt("acceptance_rate", 0),
    cancellationRate = optInt("cancellation_rate", 0),
    rating = doubleOrNull("avg_rating") ?: 0.0,
    ratingCount = optInt("rating_count", 0),
    totalEarnings = doubleOrNull("total_earnings") ?: 0.0
)

/**
 * The rider workflow (spec §7.6/§7.7) as a single source of truth: which action
 * is next, which endpoint it calls, and whether a verification code is required.
 */
data class RiderAction(val label: String, val path: String, val requiresOtp: Boolean)

/**
 * The next step for a job, worded for the product it belongs to.
 *
 * The endpoint sequence is identical for both — that is the whole point of one
 * dispatch engine — so only the labels and the final verification differ.
 */
fun nextRiderAction(status: String, mode: MovoServiceMode = MovoServiceMode.Delivery): RiderAction? =
    if (mode.isRide) when (status) {
        "assigned" -> RiderAction("Start route to passenger", "going-pickup", false)
        "going_pickup" -> RiderAction("I have arrived", "arrive-pickup", false)
        "arrived_pickup" -> RiderAction("Confirm boarding code", "verify-pickup", true)
        "picked_up" -> RiderAction("Start trip", "in-transit", false)
        "in_transit" -> RiderAction("I have reached the drop-off", "arrive-dest", false)
        // No code at the end: the passenger arrived with the rider.
        "arrived_dest" -> RiderAction("Complete trip", "complete", false)
        else -> null
    } else when (status) {
        "assigned" -> RiderAction("Start route to pickup", "going-pickup", false)
        "going_pickup" -> RiderAction("I have arrived at pickup", "arrive-pickup", false)
        "arrived_pickup" -> RiderAction("Verify pickup and collect", "verify-pickup", true)
        "picked_up" -> RiderAction("Start delivery", "in-transit", false)
        "in_transit" -> RiderAction("I have arrived at destination", "arrive-dest", false)
        "arrived_dest" -> RiderAction("Confirm delivery", "complete", true)
        else -> null
    }
