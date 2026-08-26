package com.movo.customer.send

import com.movo.customer.model.Coordinate
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
            assertFalse(DiscoverySnapshot(phase, pickupA, riderCount = 1).canContinue())
        }
    }

    @Test
    fun available_cannot_continue_without_a_valid_pickup() {
        assertFalse(DiscoverySnapshot(DiscoveryPhase.Available, null, riderCount = 1).canContinue())
        assertFalse(
            DiscoverySnapshot(
                DiscoveryPhase.Available,
                Coordinate(Double.NaN, 30.0619),
                riderCount = 1
            ).canContinue()
        )
        assertFalse(
            DiscoverySnapshot(
                DiscoveryPhase.Available,
                Coordinate(91.0, 30.0619),
                riderCount = 1
            ).canContinue()
        )
    }

    @Test
    fun available_cannot_continue_without_riders() {
        assertFalse(DiscoverySnapshot(DiscoveryPhase.Available, pickupA).canContinue())
    }

    @Test
    fun available_can_continue_with_valid_pickup_and_rider() {
        assertTrue(DiscoverySnapshot(DiscoveryPhase.Available, pickupA, riderCount = 1).canContinue())
    }

    @Test
    fun pickup_change_clears_count_and_returns_to_scanning() {
        val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, riderCount = 3)

        val changed = old.invalidateForPickup(pickupB)

        assertEquals(DiscoveryPhase.Scanning, changed.phase)
        assertEquals(pickupB, changed.pickup)
        assertEquals(0, changed.riderCount)
    }

    @Test
    fun null_pickup_clears_count_and_requires_manual_pickup() {
        val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, riderCount = 3)

        val changed = old.invalidateForPickup(null)

        assertEquals(DiscoveryPhase.ManualPickupRequired, changed.phase)
        assertNull(changed.pickup)
        assertEquals(0, changed.riderCount)
    }

    @Test
    fun invalid_pickup_clears_count_and_requires_manual_pickup() {
        val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, riderCount = 3)

        val changed = old.invalidateForPickup(Coordinate(Double.POSITIVE_INFINITY, 30.0619))

        assertEquals(DiscoveryPhase.ManualPickupRequired, changed.phase)
        assertNull(changed.pickup)
        assertEquals(0, changed.riderCount)
    }
}
