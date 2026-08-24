package com.movo.customer.send

import com.movo.customer.model.Coordinate
import com.movo.customer.model.NearbyRider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendDiscoveryStateTest {
    private val pickupA = Coordinate(-1.9441, 30.0619)
    private val pickupB = Coordinate(-1.9500, 30.0700)

    @Test
    fun non_available_states_cannot_continue() {
        val phases = listOf(
            DiscoveryPhase.Locating,
            DiscoveryPhase.ManualPickupRequired,
            DiscoveryPhase.Scanning,
            DiscoveryPhase.NoRiders,
            DiscoveryPhase.OutOfServiceArea,
            DiscoveryPhase.Offline,
            DiscoveryPhase.Error("Unable to scan for riders")
        )

        phases.forEach { phase ->
            assertFalse(DiscoverySnapshot(phase, pickupA, listOf(rider())).canContinue())
        }
    }

    @Test
    fun available_cannot_continue_without_a_valid_pickup() {
        assertFalse(DiscoverySnapshot(DiscoveryPhase.Available, null, listOf(rider())).canContinue())
        assertFalse(
            DiscoverySnapshot(
                DiscoveryPhase.Available,
                Coordinate(Double.NaN, 30.0619),
                listOf(rider())
            ).canContinue()
        )
        assertFalse(
            DiscoverySnapshot(
                DiscoveryPhase.Available,
                Coordinate(91.0, 30.0619),
                listOf(rider())
            ).canContinue()
        )
    }

    @Test
    fun available_cannot_continue_without_riders() {
        assertFalse(DiscoverySnapshot(DiscoveryPhase.Available, pickupA).canContinue())
    }

    @Test
    fun available_can_continue_with_valid_pickup_and_rider() {
        assertTrue(DiscoverySnapshot(DiscoveryPhase.Available, pickupA, listOf(rider())).canContinue())
    }

    @Test
    fun pickup_change_clears_riders_and_returns_to_scanning() {
        val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, listOf(rider()))

        val changed = old.invalidateForPickup(pickupB)

        assertEquals(DiscoveryPhase.Scanning, changed.phase)
        assertEquals(pickupB, changed.pickup)
        assertTrue(changed.riders.isEmpty())
    }

    @Test
    fun null_pickup_clears_riders_and_requires_manual_pickup() {
        val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, listOf(rider()))

        val changed = old.invalidateForPickup(null)

        assertEquals(DiscoveryPhase.ManualPickupRequired, changed.phase)
        assertNull(changed.pickup)
        assertTrue(changed.riders.isEmpty())
    }

    @Test
    fun invalid_pickup_clears_riders_and_requires_manual_pickup() {
        val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, listOf(rider()))

        val changed = old.invalidateForPickup(Coordinate(Double.POSITIVE_INFINITY, 30.0619))

        assertEquals(DiscoveryPhase.ManualPickupRequired, changed.phase)
        assertNull(changed.pickup)
        assertTrue(changed.riders.isEmpty())
    }

    private fun rider() = NearbyRider(
        id = "rider-1",
        name = "Aline",
        rating = 4.9,
        distanceKm = 0.8,
        location = Coordinate(-1.9430, 30.0625),
        locationUpdatedAt = "2026-07-29T12:00:00Z",
        vehicleMake = "TVS",
        vehicleModel = "HLX",
        vehiclePlate = "RAA 001A",
        vehicleColor = "Black"
    )
}
