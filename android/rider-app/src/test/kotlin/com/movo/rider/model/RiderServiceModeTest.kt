package com.movo.rider.model

import com.movo.design.MovoServiceMode
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rider workflow is one endpoint sequence for both products. What differs is
 * the wording and the final verification, and getting the latter wrong would
 * either strand a rider at a completed trip or let a parcel be handed over
 * unverified — so both directions are pinned here.
 */
class RiderServiceModeTest {

    private fun activeDelivery(status: String, mode: String) = ActiveDelivery(
        id = "d1", orderNo = "MV1", status = status, serviceType = if (mode == "ride") "ride" else "parcel",
        pickupAddress = "Kacyiru", pickupName = "A", pickupPhone = "+250780000001",
        pickupLat = null, pickupLng = null,
        destinationAddress = "Remera", destinationName = "B", destinationPhone = "+250780000002",
        destinationLat = null, destinationLng = null,
        earnings = 1200.0, distanceKm = 3.0, itemDescription = null, specialInstructions = null,
        serviceMode = mode
    )

    @Test
    fun `both products walk the same endpoint sequence`() {
        val statuses = listOf("assigned", "going_pickup", "arrived_pickup", "picked_up", "in_transit", "arrived_dest")
        val ridePaths = statuses.map { nextRiderAction(it, MovoServiceMode.Ride)?.path }
        val deliveryPaths = statuses.map { nextRiderAction(it, MovoServiceMode.Delivery)?.path }
        assertEquals(deliveryPaths, ridePaths, "one dispatch engine means one sequence of calls")
        assertEquals(
            listOf("going-pickup", "arrive-pickup", "verify-pickup", "in-transit", "arrive-dest", "complete"),
            ridePaths
        )
    }

    @Test
    fun `a terminal status offers no next action in either product`() {
        listOf("delivered", "cancelled", "failed").forEach { status ->
            assertNull(nextRiderAction(status, MovoServiceMode.Ride))
            assertNull(nextRiderAction(status, MovoServiceMode.Delivery))
        }
    }

    @Test
    fun `boarding is verified in both products but arrival only for a delivery`() {
        assertTrue(nextRiderAction("arrived_pickup", MovoServiceMode.Ride)!!.requiresOtp)
        assertTrue(nextRiderAction("arrived_pickup", MovoServiceMode.Delivery)!!.requiresOtp)

        assertFalse(
            nextRiderAction("arrived_dest", MovoServiceMode.Ride)!!.requiresOtp,
            "a passenger arrived with the rider — demanding a code would strand the trip"
        )
        assertTrue(
            nextRiderAction("arrived_dest", MovoServiceMode.Delivery)!!.requiresOtp,
            "a parcel is received by someone else and must still be verified"
        )
    }

    @Test
    fun `the active job agrees with the action table about when a code is needed`() {
        assertTrue(activeDelivery("arrived_pickup", "ride").requiresVerification)
        assertFalse(activeDelivery("arrived_dest", "ride").requiresVerification)
        assertTrue(activeDelivery("arrived_pickup", "delivery").requiresVerification)
        assertTrue(activeDelivery("arrived_dest", "delivery").requiresVerification)
        assertFalse(activeDelivery("in_transit", "ride").requiresVerification)
    }

    @Test
    fun `the default mode keeps every pre-existing caller on the delivery workflow`() {
        assertEquals(nextRiderAction("arrived_dest", MovoServiceMode.Delivery), nextRiderAction("arrived_dest"))
        assertEquals(MovoServiceMode.Delivery, activeDelivery("assigned", "delivery").mode)
    }

    @Test
    fun `an offer parses its product and passenger load from the platform`() {
        val ride = JSONObject()
            .put("offer_id", "o1").put("id", "d1").put("order_no", "MV1")
            .put("service_mode", "ride").put("service_type", "ride")
            .put("pickup_address", "Kacyiru").put("dest_address", "Remera")
            .put("rider_earnings", 1200).put("distance_km", 3.0)
            .put("passenger_count", 2).put("has_luggage", 1)
            .toDeliveryOffer()

        assertEquals(MovoServiceMode.Ride, ride.mode)
        assertEquals(2, ride.passengerCount)
        assertTrue(ride.hasLuggage)

        // A payload from before dual mode still parses, as a delivery.
        val legacy = JSONObject()
            .put("offer_id", "o2").put("id", "d2").put("order_no", "MV2")
            .put("service_type", "parcel")
            .put("pickup_address", "Kacyiru").put("dest_address", "Remera")
            .toDeliveryOffer()
        assertEquals(MovoServiceMode.Delivery, legacy.mode)
        assertEquals(1, legacy.passengerCount)
        assertFalse(legacy.hasLuggage)
    }

    @Test
    fun `a rider profile defaults to working both products`() {
        val profile = JSONObject().put("full_name", "Rider").toRiderProfile()
        assertTrue(profile.acceptsRides)
        assertTrue(profile.acceptsDeliveries)
        assertTrue(profile.hasAnyService)

        val ridesOnly = JSONObject()
            .put("full_name", "Rider").put("accepts_rides", 1).put("accepts_deliveries", 0)
            .toRiderProfile()
        assertTrue(ridesOnly.acceptsRides)
        assertFalse(ridesOnly.acceptsDeliveries)
        assertTrue(ridesOnly.hasAnyService)
    }

    @Test
    fun `earnings split by product and survive a payload without the breakdown`() {
        val withSplit = JSONObject()
            .put("total_earnings", 5000).put("count", 4).put("total_fees", 1000)
            .put("by_mode", JSONObject()
                .put("ride", JSONObject().put("count", 3).put("total_earnings", 3000))
                .put("delivery", JSONObject().put("count", 1).put("total_earnings", 2000)))
            .toEarningsSummary("today")
        assertEquals(3, withSplit.rides.count)
        assertEquals(2000.0, withSplit.deliveries.total)
        assertTrue(withSplit.worksBothProducts)

        val legacy = JSONObject().put("total_earnings", 900).put("count", 1).toEarningsSummary("today")
        assertEquals(0, legacy.rides.count)
        assertFalse(legacy.worksBothProducts, "a rider with no split must not be shown an empty breakdown")
    }
}
