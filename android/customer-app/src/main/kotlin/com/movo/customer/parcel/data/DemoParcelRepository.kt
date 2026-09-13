package com.movo.customer.parcel.data

import com.movo.customer.parcel.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicitly selected offline sandbox. Never constructed as a network fallback. */
class DemoParcelRepository : ParcelRepository {
    override val isDemo = true
    private val currentProfile = MutableStateFlow<ParcelProfile?>(null)
    override val profile = currentProfile.asStateFlow()
    private var challenge: Pair<String, String>? = null
    private val preferences = MutableStateFlow(ParcelSettings())
    override val settings = preferences.asStateFlow()
    private val addresses = linkedMapOf<String, SavedParcelAddress>()
    private val recipients = linkedMapOf<String, SavedRecipient>()
    override suspend fun refreshProfile(): Result<ParcelProfile> = parcelResult { authenticated(); profile.value!! }
    override suspend fun updateProfile(name: String, email: String?): Result<ParcelProfile> = parcelResult {
        authenticated(); require(name.isNotBlank()) { "Name required" }
        profile.value!!.copy(name=name.trim(), email=email).also { currentProfile.value=it }
    }
    override suspend fun signOut(): Result<Unit> = parcelResult {
        currentProfile.value=null; challenge=null; deliveries.clear(); bookings.clear(); addresses.clear(); recipients.clear()
    }
    override suspend fun updateSettings(settings: ParcelSettings): Result<Unit> = parcelResult { preferences.value=settings }
    override suspend fun savedAddresses(): Result<List<SavedParcelAddress>> = parcelResult { authenticated(); addresses.values.sortedByDescending { it.isDefault } }
    override suspend fun saveAddress(address: SavedParcelAddress): Result<SavedParcelAddress> = parcelResult {
        authenticated(); require(address.label.isNotBlank() && address.place.address.isNotBlank())
        if (address.id.isNotBlank()) throw ParcelException("unsupported", "Delete and add an address to change it")
        if (address.isDefault) addresses.replaceAll { _, value -> value.copy(isDefault=false) }
        address.copy(id="demo-address-${java.util.UUID.randomUUID()}").also { addresses[it.id]=it }
    }
    override suspend fun deleteAddress(id: String): Result<Unit> = parcelResult { authenticated(); addresses.remove(id); Unit }
    override suspend fun savedRecipients(): Result<List<SavedRecipient>> = parcelResult { authenticated(); recipients.values.toList() }
    override suspend fun saveRecipient(recipient: SavedRecipient): Result<SavedRecipient> = parcelResult {
        authenticated(); require(recipient.name.isNotBlank())
        recipient.copy(id = recipient.id.ifBlank { "demo-recipient-${java.util.UUID.randomUUID()}" }).also { recipients[it.id] = it }
    }
    override suspend fun deleteRecipient(id: String): Result<Unit> = parcelResult { authenticated(); recipients.remove(id); Unit }
    override suspend fun rate(id: String, score: Int, review: String): Result<Unit> = parcelResult {
        require(score in 1..5); val delivery=detail(id).getOrThrow()
        if (delivery.status != "delivered" || delivery.rating != null) throw ParcelException("invalid_state", "Only unrated completed deliveries can be rated")
        deliveries[id]=delivery.copy(rating=score)
    }
    private val places = listOf(
        ParcelPlace("demo-kigali-centre", "Kigali City Tower", "KN 2 Street, Kigali", -1.9441, 30.0619),
        ParcelPlace("demo-kimironko", "Kimironko Market", "KG 11 Avenue, Kigali", -1.9385, 30.1262),
        ParcelPlace("demo-airport", "Kigali International Airport", "KN 5 Road, Kigali", -1.9686, 30.1395)
    )
    private val deliveries = linkedMapOf<String, ParcelDelivery>()
    private val bookings = mutableMapOf<String, Pair<ParcelDraft, String>>()
    private fun authenticated() { if (profile.value == null) throw ParcelException("unauthorized", "Sign in to the demo first") }
    override suspend fun searchPlaces(query: String): Result<List<ParcelPlace>> = parcelResult {
        places.filter { it.label.contains(query.trim(), true) || it.address.contains(query.trim(), true) }
    }
    override suspend fun estimate(draft: ParcelDraft): Result<ParcelEstimate> = parcelResult {
        authenticated(); validateDraft(draft)
        val route = listOf(draft.pickup!!) + draft.extraStops + draft.destination!!
        val distance = route.zipWithNext().sumOf { (a,b) ->
            val lat = Math.toRadians(b.latitude - a.latitude); val lng = Math.toRadians(b.longitude - a.longitude)
            val h = kotlin.math.sin(lat/2).let { it*it } + kotlin.math.cos(Math.toRadians(a.latitude))*kotlin.math.cos(Math.toRadians(b.latitude))*kotlin.math.sin(lng/2).let { it*it }
            6371 * 2 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0,1.0))) * 1.25
        }
        val multiplier = (if (draft.tier == "express") 1.4 else 1.0) * (when (draft.size) { "large" -> 1.6; "medium" -> 1.2; else -> 1.0 })
        ParcelEstimate(kotlin.math.ceil((1000 + distance*300)*multiplier/100)*100, distance, (10+distance*3).toInt(), isDemo=true)
    }
    override suspend fun create(draft: ParcelDraft, idempotencyKey: String): Result<ParcelDelivery> = parcelResult {
        authenticated(); require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 128)
        bookings[idempotencyKey]?.let { (old, id) ->
            if (old != draft) throw ParcelException("idempotency_conflict", "This booking key belongs to a different request")
            return@parcelResult deliveries.getValue(id)
        }
        validateContacts(draft)
        val quote = estimate(draft).getOrThrow()
        val id = "demo-${java.util.UUID.randomUUID()}"
        val delivery = ParcelDelivery(id, "DEMO-${deliveries.size+1001}", "searching", draft.pickup!!, draft.destination!!,
            draft.recipientName, draft.recipientPhone, draft.description, quote.amount, java.time.Instant.now().toString(), pickupCode="4821", deliveryCode="7392", isDemo=true)
        deliveries[id] = delivery; bookings[idempotencyKey] = draft to id; delivery
    }
    override suspend fun history(): Result<List<ParcelDelivery>> = parcelResult { authenticated(); deliveries.values.toList().asReversed() }
    override suspend fun detail(id: String): Result<ParcelDelivery> = parcelResult { authenticated(); deliveries[id] ?: throw ParcelException("not_found", "Demo parcel not found") }
    override suspend fun track(id: String): Result<ParcelTracking> = parcelResult {
        val old = detail(id).getOrThrow()
        val elapsed = java.time.Duration.between(java.time.Instant.parse(old.createdAt), java.time.Instant.now()).seconds
        val status = if (old.isActive) demoStatus(elapsed) else old.status
        val delivery = old.copy(status = status, riderName = if (status != "searching") "Jean · Demo Rider" else null,
            riderPlate = if (status != "searching") "DEMO RF 482 A" else null, riderRating = if (status != "searching") 4.9 else null)
        deliveries[id] = delivery
        val progress = ((elapsed - 25).toDouble() / 45).coerceIn(0.0, 1.0)
        val events = listOf(0L to "created", 10L to "assigned", 25L to "picked_up", 40L to "in_transit", 70L to "delivered")
            .filter { it.first <= elapsed }.map { (seconds, stage) -> ParcelEvent(stage, "Demo $stage", java.time.Instant.parse(old.createdAt).plusSeconds(seconds).toString()) }
        ParcelTracking(delivery, events,
            delivery.pickup.latitude + (delivery.destination.latitude-delivery.pickup.latitude)*progress,
            delivery.pickup.longitude + (delivery.destination.longitude-delivery.pickup.longitude)*progress,
            java.time.Instant.now().toString())
    }
    override suspend fun cancel(id: String, reason: String): Result<Unit> = parcelResult {
        val delivery = detail(id).getOrThrow(); require(reason.length <= 240)
        if (!delivery.canCancel) throw ParcelException("invalid_state", "Cannot cancel at this stage")
        deliveries[id] = delivery.copy(status="cancelled")
    }
    override suspend fun requestOtp(phone: String, name: String?): Result<OtpChallenge> = parcelResult {
        require(phone.matches(Regex("\\+?[0-9]{9,15}"))) { "Enter a valid mobile number" }
        challenge = phone to (name?.trim()?.takeIf { it.isNotEmpty() } ?: "Alex Mugisha")
        OtpChallenge(phone, "Demo only — enter 123456. No SMS sent.", "123456")
    }
    override suspend fun verifyOtp(phone: String, code: String): Result<ParcelProfile> = parcelResult {
        val pending = challenge
        if (pending?.first != phone || code != "123456") throw ParcelException("invalid_otp", "Request a demo code and enter 123456")
        ParcelProfile("demo-customer", pending.second, phone).also {
            currentProfile.value = it; challenge = null
            if (deliveries.isEmpty()) {
                val seed = ParcelDelivery("demo-completed", "DEMO-1000", "delivered", places[0], places[1], "Grace Uwase", "+250788654321", "Books and stationery", 2400.0, "2026-09-10T11:30:00Z", riderName="Jean Habimana", riderPhone="+250788111222", isDemo=true, riderPlate="RF 482 A", riderRating=4.9)
                deliveries[seed.id]=seed
            }
        }
    }
}
