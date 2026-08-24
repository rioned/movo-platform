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

data class DiscoverySnapshot(
    val phase: DiscoveryPhase,
    val pickup: Coordinate? = null,
    val riders: List<NearbyRider> = emptyList()
) {
    fun canContinue(): Boolean =
        phase == DiscoveryPhase.Available && pickup?.isFinite == true && riders.isNotEmpty()

    fun invalidateForPickup(next: Coordinate?): DiscoverySnapshot = DiscoverySnapshot(
        phase = if (next?.isFinite == true) {
            DiscoveryPhase.Scanning
        } else {
            DiscoveryPhase.ManualPickupRequired
        },
        pickup = next?.takeIf { it.isFinite }
    )
}
