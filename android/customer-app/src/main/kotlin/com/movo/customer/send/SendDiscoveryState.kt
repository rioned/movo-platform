package com.movo.customer.send

import com.movo.customer.model.Coordinate
import com.movo.customer.model.NearbyRider

sealed interface DiscoveryPhase {
    data object Locating : DiscoveryPhase
    data object ManualPickupRequired : DiscoveryPhase
    data object Scanning : DiscoveryPhase
    data object Available : DiscoveryPhase
    data object NoRiders : DiscoveryPhase
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
