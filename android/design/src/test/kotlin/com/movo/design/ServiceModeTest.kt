package com.movo.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The mode vocabulary is what stops a passenger being told their "item" was
 * collected, so these tests pin the wording as behaviour rather than decoration.
 */
class ServiceModeTest {

    @Test
    fun `an unknown or missing mode reads as the product that predates the split`() {
        assertEquals(MovoServiceMode.Delivery, MovoServiceMode.from(null))
        assertEquals(MovoServiceMode.Delivery, MovoServiceMode.from(""))
        assertEquals(MovoServiceMode.Delivery, MovoServiceMode.from("teleport"))
        assertEquals(MovoServiceMode.Ride, MovoServiceMode.from("ride"))
        assertEquals(MovoServiceMode.Ride, MovoServiceMode.from(" RIDE "))
    }

    @Test
    fun `a row carrying only a service type still resolves to its product`() {
        assertEquals(MovoServiceMode.Ride, MovoServiceMode.forServiceType("ride"))
        assertEquals(MovoServiceMode.Delivery, MovoServiceMode.forServiceType("parcel"))
        assertEquals(MovoServiceMode.Delivery, MovoServiceMode.forServiceType("document"))
        // Historic rows predate service_mode entirely.
        assertEquals(MovoServiceMode.Delivery, MovoServiceMode.forServiceType(null))
    }

    @Test
    fun `every service type belongs to exactly one product`() {
        val seen = mutableSetOf<String>()
        MovoServiceMode.entries.forEach { mode ->
            mode.serviceTypes.forEach { type ->
                assertTrue(seen.add(type), "$type is claimed by more than one mode")
            }
            assertTrue(
                mode.defaultServiceType in mode.serviceTypes,
                "${mode.apiValue} defaults to a service type it does not own"
            )
        }
    }

    @Test
    fun `no stage tells a passenger about an item or a delivery`() {
        val cargoWords = listOf("item", "parcel", "deliver", "sender", "recipient")
        MovoDeliveryStage.entries.forEach { stage ->
            val customer = stage.customerLabel(MovoServiceMode.Ride).lowercase()
            val rider = stage.riderLabel(MovoServiceMode.Ride).lowercase()
            cargoWords.forEach { word ->
                assertFalse(customer.contains(word), "ride customer label '${stage.customerLabel(MovoServiceMode.Ride)}' mentions '$word'")
                assertFalse(rider.contains(word), "ride rider label '${stage.riderLabel(MovoServiceMode.Ride)}' mentions '$word'")
            }
        }
    }

    @Test
    fun `delivery wording is unchanged by the mode overloads`() {
        MovoDeliveryStage.entries.forEach { stage ->
            assertEquals(stage.customerLabel, stage.customerLabel(MovoServiceMode.Delivery))
            assertEquals(stage.riderLabel, stage.riderLabel(MovoServiceMode.Delivery))
        }
        assertEquals(MovoDeliveryStage.trackedSteps, trackedSteps(MovoServiceMode.Delivery))
    }

    @Test
    fun `the two products read differently at the moments that matter`() {
        // Collection and arrival are where confusing the products would be worst.
        listOf(MovoDeliveryStage.PickedUp, MovoDeliveryStage.ArrivedDest, MovoDeliveryStage.Delivered).forEach { stage ->
            assertNotEquals(
                stage.customerLabel(MovoServiceMode.Delivery),
                stage.customerLabel(MovoServiceMode.Ride),
                "$stage reads identically for a passenger and a parcel"
            )
        }
    }

    @Test
    fun `both progress bars have the same number of checkpoints`() {
        // DeliveryProgress indexes these by MovoDeliveryStage#trackedStep, so a
        // mismatched length would silently drop or mislabel a step.
        assertEquals(
            trackedSteps(MovoServiceMode.Delivery).size,
            trackedSteps(MovoServiceMode.Ride).size
        )
        val maxStep = MovoDeliveryStage.entries.maxOf { it.trackedStep }
        assertTrue(
            maxStep < trackedSteps(MovoServiceMode.Ride).size,
            "a stage maps past the end of the ride progress bar"
        )
    }

    @Test
    fun `only a delivery asks for a code at the destination`() {
        assertTrue(requiresHandoverCode(MovoServiceMode.Delivery))
        assertFalse(
            requiresHandoverCode(MovoServiceMode.Ride),
            "a passenger arrives with the rider — there is nothing left to verify"
        )
    }

    @Test
    fun `map endpoints are named for the product`() {
        assertEquals("Drop-off", destinationLabel(MovoServiceMode.Ride))
        assertEquals("Destination", destinationLabel(MovoServiceMode.Delivery))
        assertEquals("Moto ride", serviceLabel("ride", MovoServiceMode.Ride))
        assertEquals("Parcel", serviceLabel("parcel", MovoServiceMode.Delivery))
        assertEquals("Document", serviceLabel("document", MovoServiceMode.Delivery))
    }
}
