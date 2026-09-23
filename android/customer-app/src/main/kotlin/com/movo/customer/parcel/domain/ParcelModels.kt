package com.movo.customer.parcel.domain

import kotlinx.coroutines.CancellationException

 data class ParcelProfile(val id: String, val name: String, val phone: String, val email: String? = null)
data class OtpChallenge(val phone: String, val message: String, val demoCode: String? = null)
data class ParcelPlace(val id: String, val label: String, val address: String, val latitude: Double, val longitude: Double)
data class ParcelDraft(
    val pickup: ParcelPlace? = null, val destination: ParcelPlace? = null,
    val senderName: String = "", val senderPhone: String = "", val recipientName: String = "", val recipientPhone: String = "",
    val description: String = "", val serviceType: String = "parcel", val paymentMethod: String = "cash", val instructions: String = "",
    val tier: String = "standard", val size: String = "small", val category: String = "general",
    val extraStops: List<ParcelPlace> = emptyList(), val cashOnDelivery: Double = 0.0,
    val photoUri: String? = null, val notifySms: Boolean = false,
    // The rider the customer picked from the nearby list. Null means "any available
    // rider", which is the original automatic nearest-rider dispatch.
    val preferredRiderId: String? = null, val preferredRiderLabel: String? = null
)

/**
 * A rider near the pickup that the customer can choose to send with. Carries only what
 * decides a handover — the plate to look for at the kerb, the rider's standing and how
 * soon they can arrive. No phone number or legal name: until a rider accepts the job
 * there is nothing agreed between the two parties.
 */
data class NearbyParcelRider(
    val id: String,
    val plate: String? = null,
    val make: String? = null,
    val type: String? = null,
    val color: String? = null,
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val distanceKm: Double = 0.0,
    val etaMinutes: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val zone: String? = null,
    val sameZone: Boolean = false
) {
    val label: String get() = plate?.takeIf(String::isNotBlank) ?: "MOVO rider"
    val vehicle: String get() = listOfNotNull(
        color?.takeIf(String::isNotBlank), make?.takeIf(String::isNotBlank), type?.takeIf(String::isNotBlank)
    ).joinToString(" ")
}
data class ParcelEstimate(val amount: Double, val distanceKm: Double, val etaMinutes: Int?, val currency: String = "RWF", val isDemo: Boolean = false)
data class ParcelDelivery(
    val id: String, val reference: String, val status: String, val pickup: ParcelPlace, val destination: ParcelPlace,
    val recipientName: String, val recipientPhone: String, val description: String, val amount: Double, val createdAt: String,
    val currency: String = "RWF", val riderName: String? = null, val riderPhone: String? = null,
    val pickupCode: String? = null, val deliveryCode: String? = null, val rating: Int? = null, val isDemo: Boolean = false,
    val riderPlate: String? = null, val riderRating: Double? = null
) {
    val isActive: Boolean get() = status !in setOf("delivered", "cancelled", "failed")
    val canCancel: Boolean get() = status in setOf("scheduled", "created", "searching", "assigned")
}
data class ParcelEvent(val status: String, val description: String, val createdAt: String)
data class ParcelTracking(val delivery: ParcelDelivery, val events: List<ParcelEvent>, val riderLatitude: Double? = null, val riderLongitude: Double? = null, val updatedAt: String? = null)
data class SavedParcelAddress(val id: String = "", val label: String, val place: ParcelPlace, val contactName: String = "", val contactPhone: String = "", val isDefault: Boolean = false)
/** A reusable receiver profile the customer keeps on this device — e.g. a family
 * member at home who receives forgotten keys or other parcels sent from outside. */
data class SavedRecipient(val id: String = "", val name: String, val phone: String, val note: String = "")
data class ParcelSettings(val notificationsEnabled: Boolean = true, val darkMode: Boolean = false, val language: String = "en")
class ParcelException(val code: String, override val message: String) : Exception(message)

/** Unlike runCatching, never turns structured-concurrency cancellation into an application error. */
internal suspend fun <T> parcelResult(block: suspend () -> T): Result<T> = try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
