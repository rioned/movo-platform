package com.movo.customer.parcel.data

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ParcelRepositoryTest {
    @Test fun wireEnvelopePreservesErrorsAndDeliveryMapping() {
        assertEquals("bad_otp", assertFailsWith<com.movo.customer.parcel.domain.ParcelException> { ParcelWire.unwrap(mapOf("success" to false,"error" to "Wrong code","code" to "bad_otp")) }.code)
        val data = mapOf<String,Any?>("id" to "d1","order_no" to "MV1","status" to "searching","pickup_address" to "Home","dest_address" to "Work","pickup_lat" to -1.9,"pickup_lng" to 30.0,"dest_lat" to -1.8,"dest_lng" to 30.1,"total_charge" to 1200,"created_at" to "2026-09-10T10:00:00Z")
        assertEquals("MV1", ParcelWire.delivery(ParcelWire.unwrap(mapOf("success" to true,"data" to data)).objectValue()).reference)
        assertFailsWith<com.movo.customer.parcel.domain.ParcelException> { ParcelWire.unwrap(mapOf("data" to data)) }
    }
    @Test fun demoAccountAddressesSettingsAndRatings() = runTest {
        val repo = DemoParcelRepository()
        repo.requestOtp("+250788123456", "Alex"); repo.verifyOtp("+250788123456", "123456")
        assertEquals("Grace", repo.updateProfile("Grace", "grace@example.com").getOrThrow().name)
        assertEquals("Grace", repo.refreshProfile().getOrThrow().name)
        val place = repo.searchPlaces("Kigali").getOrThrow().first()
        val address = repo.saveAddress(com.movo.customer.parcel.domain.SavedParcelAddress(label="Home", place=place)).getOrThrow()
        assertTrue(repo.savedAddresses().getOrThrow().contains(address))
        repo.deleteAddress(address.id).getOrThrow()
        assertFalse(repo.savedAddresses().getOrThrow().contains(address))
        repo.updateSettings(com.movo.customer.parcel.domain.ParcelSettings(darkMode=true)).getOrThrow()
        assertTrue(repo.settings.value.darkMode)
        val delivered = repo.history().getOrThrow().first { it.status == "delivered" }
        repo.rate(delivered.id, 5, "Great").getOrThrow()
        assertEquals(5, repo.detail(delivered.id).getOrThrow().rating)
        assertTrue(repo.rate(delivered.id, 4).isFailure)
        repo.signOut().getOrThrow()
        assertNull(repo.profile.value)
        assertTrue(repo.history().isFailure)
    }
    @Test fun liveWireRejectsUnsupportedOptionsAndMapsServerFields() {
        val p = com.movo.customer.parcel.domain.ParcelPlace("p", "Home", "Kigali", -1.94,30.06)
        val draft = com.movo.customer.parcel.domain.ParcelDraft(p,p,"Alex","+250788123456","Grace","+250788654321")
        val payload = ParcelWire.booking(draft)
        assertEquals("parcel", payload["service_type"])
        assertEquals("Kigali", payload["dest_address"])
        assertFailsWith<com.movo.customer.parcel.domain.ParcelException> { ParcelWire.booking(draft.copy(tier="express")) }
        assertFailsWith<com.movo.customer.parcel.domain.ParcelException> { ParcelWire.booking(draft.copy(notifySms=true)) }
        val quote = ParcelWire.estimate(mapOf("totalCharge" to 2300.0,"distance_km" to 4.2))
        assertEquals(2300.0, quote.amount); assertNull(quote.etaMinutes)
    }
    @Test fun demoBookingIsIdempotentAndCancellable() = runTest {
        val repo = DemoParcelRepository()
        repo.requestOtp("+250788123456"); repo.verifyOtp("+250788123456", "123456")
        val places = repo.searchPlaces("Kigali").getOrThrow()
        val draft = com.movo.customer.parcel.domain.ParcelDraft(places.first(), places.last(), "Alex", "+250788123456", "Grace", "+250788654321", "Books")
        assertTrue(repo.estimate(draft).getOrThrow().amount > 0)
        val delivery = repo.create(draft, "booking-1").getOrThrow()
        assertEquals(delivery, repo.create(draft, "booking-1").getOrThrow())
        assertTrue(repo.create(draft.copy(description = "Changed"), "booking-1").isFailure)
        assertEquals(delivery, repo.detail(delivery.id).getOrThrow())
        assertTrue(repo.track(delivery.id).getOrThrow().events.isNotEmpty())
        assertTrue(repo.history().getOrThrow().any { it.id == delivery.id })
        assertTrue(repo.cancel(delivery.id, "Plans changed").isSuccess)
        assertEquals("cancelled", repo.detail(delivery.id).getOrThrow().status)
        assertTrue(repo.cancel(delivery.id, "Again").isFailure)
    }
    @Test fun demoOtpRequiresRequestAndCorrectCode() = runTest {
        val repo = DemoParcelRepository()
        assertTrue(repo.verifyOtp("+250788123456", "123456").isFailure)
        assertEquals("123456", repo.requestOtp("+250788123456").getOrThrow().demoCode)
        assertTrue(repo.verifyOtp("+250788123456", "000000").isFailure)
        assertEquals("+250788123456", repo.verifyOtp("+250788123456", "123456").getOrThrow().phone)
        assertTrue(repo.isDemo)
    }
}
