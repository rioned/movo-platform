package com.movo.rider.home

import com.movo.rider.model.ActiveDelivery
import com.movo.rider.model.DeliveryOffer
import com.movo.rider.model.nextRiderAction
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeGateway(
    var home: JSONObject = JSONObject(),
    var failWith: Exception? = null
) : RiderGateway {
    val calls = mutableListOf<Pair<String, String>>()
    var queuedResponse = false
    var synced = 0
    var pending = 0

    override suspend fun get(path: String): JSONObject {
        calls += "GET" to path
        failWith?.let { throw it }
        return home
    }

    override suspend fun put(path: String, body: JSONObject): JSONObject {
        calls += "PUT" to path
        failWith?.let { throw it }
        return if (queuedResponse) JSONObject().put("queued", true) else JSONObject()
    }

    override suspend fun post(path: String, body: JSONObject): JSONObject {
        calls += "POST" to path
        failWith?.let { throw it }
        return JSONObject()
    }

    override suspend fun syncPending(): Int { synced += 1; return 0 }
    override fun pendingCount(): Int = pending
}

private fun homePayload(
    approval: String = "approved",
    availability: String = "online",
    withOffer: Boolean = false,
    activeStatus: String? = null
): JSONObject {
    val payload = JSONObject()
        .put("full_name", "Jean Rider")
        .put("approval_status", approval)
        .put("availability", availability)
        .put("online_status", availability)
        .put("total_earnings", 12500)
        .put("total_deliveries", 7)
        .put("avg_rating", 4.6)
        .put("rating_count", 5)
    payload.put(
        "offers",
        JSONArray().apply {
            if (withOffer) put(
                JSONObject()
                    .put("offer_id", "offer-1").put("id", "delivery-1").put("order_no", "MV123")
                    .put("service_type", "parcel").put("pickup_address", "Kacyiru").put("dest_address", "Remera")
                    .put("rider_earnings", 2400).put("distance_km", 5.2)
                    .put("expires_at", "2026-07-30 09:15:00")
            )
        }
    )
    activeStatus?.let {
        payload.put(
            "activeDelivery",
            JSONObject().put("id", "delivery-9").put("order_no", "MV999").put("status", it)
                .put("service_type", "document").put("pickup_address", "Kigali Heights")
                .put("pickup_name", "Alice").put("pickup_phone", "+250788000001")
                .put("dest_address", "Kimironko").put("dest_name", "Bob").put("dest_phone", "+250788000002")
                .put("rider_earnings", 1800).put("distance_km", 3.4)
        )
    }
    return payload
}

class RiderControllerTest {

    @Test
    fun `refresh maps the platform payload into rider workspace state`() = runTest {
        val gateway = FakeGateway(home = homePayload(withOffer = true))
        gateway.pending = 2
        val controller = RiderController(gateway)

        controller.refresh()

        val state = controller.state.value
        assertEquals("Jean Rider", state.profile.name)
        assertTrue(state.profile.isApproved)
        assertTrue(state.profile.isOnline)
        assertEquals(2, state.pendingSync)
        assertNotNull(state.offer)
        assertEquals("offer-1", state.offer?.offerId)
        assertEquals(2400.0, state.offer?.earnings)
        assertNull(state.activeDelivery)
        assertEquals(1, gateway.synced, "queued mutations must be replayed before reading state")
    }

    @Test
    fun `a failed refresh surfaces an error instead of clearing the workspace`() = runTest {
        val gateway = FakeGateway(failWith = IllegalStateException("network down"))
        val controller = RiderController(gateway)

        controller.refresh()

        assertEquals(RiderMessage.Kind.Error, controller.message.value?.kind)
        assertEquals("network down", controller.message.value?.text)
    }

    @Test
    fun `verification steps refuse to advance without the customer code`() = runTest {
        val gateway = FakeGateway(home = homePayload(activeStatus = "arrived_pickup"))
        val controller = RiderController(gateway)
        controller.refresh()
        val delivery = requireNotNull(controller.state.value.activeDelivery)

        val advanced = controller.advance(delivery, otp = "")

        assertFalse(advanced)
        assertEquals(RiderMessage.Kind.Error, controller.message.value?.kind)
        assertFalse(gateway.calls.any { it.second.contains("verify-pickup") }, "no request may be sent without a code")
    }

    @Test
    fun `verification steps send the code to the correct workflow endpoint`() = runTest {
        val gateway = FakeGateway(home = homePayload(activeStatus = "arrived_dest"))
        val controller = RiderController(gateway)
        controller.refresh()
        val delivery = requireNotNull(controller.state.value.activeDelivery)

        val advanced = controller.advance(delivery, otp = "4821", recipientName = "Bob")

        assertTrue(advanced)
        assertTrue(gateway.calls.any { it == "PUT" to "/api/deliveries/delivery-9/complete" })
        assertEquals(RiderMessage.Kind.Success, controller.message.value?.kind)
    }

    @Test
    fun `an offline status update is reported as queued, not as delivered`() = runTest {
        val gateway = FakeGateway(home = homePayload(activeStatus = "assigned"))
        gateway.queuedResponse = true
        val controller = RiderController(gateway)
        controller.refresh()
        val delivery = requireNotNull(controller.state.value.activeDelivery)

        controller.advance(delivery, otp = "")

        assertEquals(RiderMessage.Kind.Info, controller.message.value?.kind)
        assertTrue(controller.message.value!!.text.contains("offline", ignoreCase = true))
    }

    @Test
    fun `declining an offer calls the offer endpoint and refreshes`() = runTest {
        val gateway = FakeGateway(home = homePayload(withOffer = true))
        val controller = RiderController(gateway)
        controller.refresh()
        val offer = requireNotNull(controller.state.value.offer)

        controller.declineOffer(offer)

        assertTrue(gateway.calls.any { it == "PUT" to "/api/mobile/v1/rider/offers/offer-1/decline" })
    }

    @Test
    fun `SOS reports post to the incident endpoint`() = runTest {
        val gateway = FakeGateway(home = homePayload())
        val controller = RiderController(gateway)

        controller.reportIncident("sos", "Rider emergency SOS", -1.94, 30.06, "delivery-9")

        assertTrue(gateway.calls.any { it == "POST" to "/api/rider/incidents" })
        assertEquals(RiderMessage.Kind.Success, controller.message.value?.kind)
    }
}

class RiderWorkflowTest {

    @Test
    fun `the workflow advances through the platform delivery lifecycle`() {
        val expected = listOf(
            "assigned" to "going-pickup",
            "going_pickup" to "arrive-pickup",
            "arrived_pickup" to "verify-pickup",
            "picked_up" to "in-transit",
            "in_transit" to "arrive-dest",
            "arrived_dest" to "complete"
        )
        expected.forEach { (status, path) ->
            assertEquals(path, nextRiderAction(status)?.path, "status $status must advance to $path")
        }
        assertNull(nextRiderAction("delivered"), "a completed delivery has no next action")
        assertNull(nextRiderAction("cancelled"))
    }

    @Test
    fun `only the two handover steps require a verification code`() {
        val requiring = listOf("assigned", "going_pickup", "arrived_pickup", "picked_up", "in_transit", "arrived_dest")
            .filter { nextRiderAction(it)?.requiresOtp == true }
        assertEquals(listOf("arrived_pickup", "arrived_dest"), requiring)
    }

    @Test
    fun `verification is required exactly at the arrival states`() {
        fun delivery(status: String) = ActiveDelivery(
            id = "d", orderNo = "MV1", status = status, serviceType = "parcel",
            pickupAddress = "a", pickupName = "n", pickupPhone = "p", pickupLat = null, pickupLng = null,
            destinationAddress = "b", destinationName = "m", destinationPhone = "q", destinationLat = null, destinationLng = null,
            earnings = 1000.0, distanceKm = 2.0, itemDescription = null, specialInstructions = null
        )
        assertTrue(delivery("arrived_pickup").requiresVerification)
        assertTrue(delivery("arrived_dest").requiresVerification)
        assertFalse(delivery("in_transit").requiresVerification)
    }
}

class OfferCountdownTest {

    @Test
    fun `SQLite UTC timestamps are parsed into a countdown`() {
        val now = Instant.parse("2026-07-30T09:14:30Z")
        assertEquals(30, offerSecondsRemaining("2026-07-30 09:15:00", now))
        assertEquals(30, offerSecondsRemaining("2026-07-30T09:15:00Z", now))
    }

    @Test
    fun `a lapsed or unreadable expiry counts as zero seconds`() {
        val now = Instant.parse("2026-07-30T09:20:00Z")
        assertEquals(0, offerSecondsRemaining("2026-07-30 09:15:00", now))
        assertEquals(0, offerSecondsRemaining(null, now))
        assertEquals(0, offerSecondsRemaining("not-a-timestamp", now))
    }

    @Test
    fun `countdown progress runs from full to empty and never outside the ring`() {
        assertEquals(1f, offerProgress(30, 30))
        assertEquals(0.5f, offerProgress(15, 30))
        assertEquals(0f, offerProgress(0, 30))
        assertEquals(1f, offerProgress(60, 30), "a longer window than expected must not overflow the ring")
        assertEquals(0f, offerProgress(10, 0))
    }

    @Test
    fun `offers keep the platform identifiers needed to accept or decline`() {
        val offer = DeliveryOffer(
            offerId = "offer-1", deliveryId = "delivery-1", orderNo = "MV1", serviceType = "parcel",
            pickupAddress = "a", destinationAddress = "b", pickupLat = null, pickupLng = null,
            destinationLat = null, destinationLng = null, earnings = 100.0, distanceKm = 1.0,
            expiresAt = null, specialInstructions = null
        )
        assertEquals("offer-1", offer.offerId)
        assertEquals("delivery-1", offer.deliveryId)
    }
}
