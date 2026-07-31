package com.movo.rider.home

import com.movo.rider.model.ActiveDelivery
import com.movo.rider.model.DeliveryOffer
import com.movo.rider.model.RiderHomeState
import com.movo.rider.model.RiderProfile
import com.movo.rider.model.nextRiderAction
import com.movo.rider.model.toActiveDelivery
import com.movo.rider.model.toDeliveryOffer
import com.movo.rider.model.toRiderProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** The network surface the rider workflow needs — narrow enough to fake in tests. */
interface RiderGateway {
    suspend fun get(path: String): JSONObject
    suspend fun put(path: String, body: JSONObject = JSONObject()): JSONObject
    suspend fun post(path: String, body: JSONObject): JSONObject
    suspend fun syncPending(): Int
    fun pendingCount(): Int
}

/**
 * Owns the rider's live workspace: availability, the current offer and the active
 * delivery. Every mutation refetches server state afterwards, so the screen shows
 * what the platform accepted — never an optimistic guess about a delivery stage.
 */
class RiderController(private val gateway: RiderGateway) {
    private val mutableState = MutableStateFlow(RiderHomeState())
    val state: StateFlow<RiderHomeState> = mutableState.asStateFlow()

    private val mutableMessage = MutableStateFlow<RiderMessage?>(null)
    val message: StateFlow<RiderMessage?> = mutableMessage.asStateFlow()

    private val refreshLock = Mutex()

    suspend fun refresh() {
        refreshLock.withLock {
            runCatching { gateway.syncPending() }
            runCatching { gateway.get("/api/mobile/v1/rider/home") }
                .onSuccess { payload -> mutableState.value = payload.toHomeState(gateway.pendingCount()) }
                .onFailure { failure -> post(RiderMessage.error(failure.message ?: "Unable to load your rider status")) }
        }
    }

    /** Availability changes are rejected by the server mid-delivery; surface that plainly. */
    suspend fun setAvailability(status: String) {
        runCatching { gateway.put("/api/rider/status", JSONObject().put("status", status)) }
            .onSuccess {
                post(RiderMessage.info(if (status == "online") "You are online and receiving offers" else "Availability set to ${status.replace('_', ' ')}"))
                refresh()
            }
            .onFailure { post(RiderMessage.error(it.message ?: "Could not change availability")) }
    }

    suspend fun acceptOffer(offer: DeliveryOffer) {
        runCatching { gateway.put("/api/deliveries/${offer.deliveryId}/accept") }
            .onSuccess {
                val noun = if (offer.mode.isRide) "Trip" else "Delivery"
                post(RiderMessage.success("$noun ${offer.orderNo} accepted"))
                refresh()
            }
            .onFailure { post(RiderMessage.error(it.message ?: "That offer is no longer available")) }
    }

    /**
     * Changes which products this rider is offered. The server refuses to leave a
     * rider with nothing enabled, and that refusal is surfaced rather than
     * swallowed — a rider silently receiving no work has no way to diagnose it.
     */
    suspend fun setServices(acceptsRides: Boolean, acceptsDeliveries: Boolean) {
        val body = JSONObject().put("accepts_rides", acceptsRides).put("accepts_deliveries", acceptsDeliveries)
        runCatching { gateway.put("/api/rider/services", body) }
            .onSuccess {
                post(RiderMessage.success(when {
                    acceptsRides && acceptsDeliveries -> "You will receive rides and deliveries"
                    acceptsRides -> "You will receive ride offers only"
                    else -> "You will receive delivery offers only"
                }))
                refresh()
            }
            .onFailure { post(RiderMessage.error(it.message ?: "Could not save your service preferences")) }
    }

    suspend fun declineOffer(offer: DeliveryOffer) {
        runCatching { gateway.put("/api/mobile/v1/rider/offers/${offer.offerId}/decline") }
            .onSuccess { post(RiderMessage.info("Offer declined")); refresh() }
            .onFailure { post(RiderMessage.error(it.message ?: "Could not decline this offer")) }
    }

    /**
     * Advances the active delivery one step. Verification codes are required at
     * the two handover points, and the caller is told before the request is sent.
     */
    suspend fun advance(delivery: ActiveDelivery, otp: String, recipientName: String? = null): Boolean {
        val action = nextRiderAction(delivery.status, delivery.mode) ?: return false
        if (action.requiresOtp && otp.length < 4) {
            post(RiderMessage.error(
                if (delivery.mode.isRide) "Ask your passenger for their boarding code"
                else "Enter the verification code from the customer"
            ))
            return false
        }
        val body = JSONObject()
        if (action.requiresOtp) body.put("otp", otp)
        recipientName?.takeIf { it.isNotBlank() }?.let { body.put("recipient_name", it) }
        var accepted = false
        runCatching { gateway.put("/api/deliveries/${delivery.id}/${action.path}", body) }
            .onSuccess { response ->
                accepted = true
                if (response.optBoolean("queued")) {
                    post(RiderMessage.info("Saved offline — MOVO will sync this update automatically"))
                } else {
                    val done = if (delivery.mode.isRide) "Trip completed" else "Delivery completed"
                    post(RiderMessage.success(if (action.path == "complete") done else "Status updated"))
                }
                refresh()
            }
            .onFailure { post(RiderMessage.error(it.message ?: "Could not update this delivery")) }
        return accepted
    }

    suspend fun reportIncident(kind: String, description: String, lat: Double?, lng: Double?, deliveryId: String?) {
        val body = JSONObject().put("kind", kind).put("description", description)
        lat?.let { body.put("lat", it) }
        lng?.let { body.put("lng", it) }
        deliveryId?.let { body.put("delivery_id", it) }
        runCatching { gateway.post("/api/rider/incidents", body) }
            .onSuccess { post(RiderMessage.success("MOVO operations has been alerted")) }
            .onFailure { post(RiderMessage.error(it.message ?: "Could not send the report")) }
    }

    suspend fun saveMotorcycle(profile: RiderProfile) {
        val body = JSONObject()
        if (profile.motorcyclePlate.isNotBlank()) body.put("motorcycle_plate", profile.motorcyclePlate)
        if (profile.motorcycleMake.isNotBlank()) body.put("motorcycle_make", profile.motorcycleMake)
        if (profile.motorcycleType.isNotBlank()) body.put("motorcycle_type", profile.motorcycleType)
        if (profile.motorcycleColor.isNotBlank()) body.put("motorcycle_color", profile.motorcycleColor)
        runCatching { gateway.put("/api/rider/profile", body) }
            .onSuccess { post(RiderMessage.success("Motorcycle details saved")); refresh() }
            .onFailure { post(RiderMessage.error(it.message ?: "Could not save your motorcycle details")) }
    }

    fun consumeMessage() { mutableMessage.value = null }

    private fun post(message: RiderMessage) { mutableMessage.value = message }
}

data class RiderMessage(val text: String, val kind: Kind) {
    enum class Kind { Info, Success, Error }
    companion object {
        fun info(text: String) = RiderMessage(text, Kind.Info)
        fun success(text: String) = RiderMessage(text, Kind.Success)
        fun error(text: String) = RiderMessage(text, Kind.Error)
    }
}

internal fun JSONObject.toHomeState(pendingSync: Int): RiderHomeState {
    val offers = optJSONArray("offers")
    val offer = if ((offers?.length() ?: 0) > 0) offers!!.getJSONObject(0).toDeliveryOffer() else null
    val active = optJSONObject("activeDelivery")?.takeIf { it.has("id") }?.toActiveDelivery()
    return RiderHomeState(
        profile = toRiderProfile(),
        offer = offer,
        activeDelivery = active,
        serverTime = optString("serverTime").takeIf(String::isNotBlank),
        pendingSync = pendingSync
    )
}

/**
 * Seconds left on an offer, clamped at zero. Timestamps arrive as SQLite UTC
 * ("2026-07-30 09:15:00"), which is not ISO-8601 until normalised.
 */
fun offerSecondsRemaining(expiresAt: String?, now: Instant = Instant.now()): Int {
    val expiry = expiresAt?.let { raw ->
        runCatching {
            val normalised = raw.trim().replace(' ', 'T').let { if (it.endsWith("Z")) it else "${it}Z" }
            Instant.parse(normalised)
        }.getOrNull()
    } ?: return 0
    return Duration.between(now, expiry).seconds.coerceIn(0, 600).toInt()
}

/** Countdown ring progress, 1f when the offer arrives and 0f when it lapses. */
fun offerProgress(secondsRemaining: Int, windowSeconds: Int = 30): Float =
    if (windowSeconds <= 0) 0f else (secondsRemaining.toFloat() / windowSeconds).coerceIn(0f, 1f)
