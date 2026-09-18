package com.movo.customer.send

import com.movo.customer.model.Coordinate
import com.movo.customer.model.NearbyRider

sealed interface DiscoveryPhase {
    data object Locating : DiscoveryPhase
    data object ManualPickupRequired : DiscoveryPhase
    data object Scanning : DiscoveryPhase
    data object Available : DiscoveryPhase
    data object NoRiders : DiscoveryPhase
    // The backend resolved this pickup to no service zone at all (spec §12) — a
    // structural "MOVO isn't here" fact, distinct from NoRiders' "nobody's online
    // right now." Rescanning the same pickup can never fix it; only moving the pin can.
    data object OutOfServiceArea : DiscoveryPhase
    data object Offline : DiscoveryPhase
    data class Error(val message: String) : DiscoveryPhase
}

/**
 * The customer's view of who can take their parcel. Riders are listed so the
 * customer can choose who they are handing it to; picking one is a preference
 * dispatch honours first, never a lock — see the discovery sheet's "any rider"
 * option, which restores the automatic nearest-rider match.
 */
data class DiscoverySnapshot(
    val phase: DiscoveryPhase,
    val pickup: Coordinate? = null,
    val riderCount: Int = 0,
    val riders: List<NearbyRider> = emptyList(),
    val selectedRiderId: String? = null
) {
    val selectedRider: NearbyRider? get() = riders.firstOrNull { it.id == selectedRiderId }

    fun canContinue(): Boolean =
        phase == DiscoveryPhase.Available && pickup?.isFinite == true && riderCount > 0

    fun withSelection(riderId: String?): DiscoverySnapshot =
        copy(selectedRiderId = riderId?.takeIf { id -> riders.any { it.id == id } })

    fun invalidateForPickup(next: Coordinate?): DiscoverySnapshot = DiscoverySnapshot(
        phase = if (next?.isFinite == true) {
            DiscoveryPhase.Scanning
        } else {
            DiscoveryPhase.ManualPickupRequired
        },
        pickup = next?.takeIf { it.isFinite }
    )
}
