package com.movo.customer.parcel.push

import org.junit.Test
import org.junit.Assert.*

class PushRulesTest {
    @Test fun dedupIsBoundedAndDoesNotCollapseDifferentEvents() {
        val dedup = PushDeduplicator(capacity = 2)
        assertTrue(dedup.accept("a"))
        assertFalse(dedup.accept("a"))
        assertTrue(dedup.accept("b"))
        assertTrue(dedup.accept("c"))
        assertTrue(dedup.accept("a"))
        assertEquals(PushRules.eventKey("m1", mapOf("order_id" to "1")), PushRules.eventKey("m1", mapOf("order_id" to "2")))
        assertNotEquals(PushRules.eventKey(null, mapOf("order_id" to "1", "status" to "pending")), PushRules.eventKey(null, mapOf("order_id" to "1", "status" to "delivered")))
        assertEquals(PushRules.eventKey(null, linkedMapOf("a" to "b", "c" to "d")), PushRules.eventKey(null, linkedMapOf("c" to "d", "a" to "b")))
    }

    @Test fun notificationGatesFailClosedForUnknownCategories() {
        assertEquals(PushCategory.OFFERS, PushRules.category(mapOf("category" to "offers")))
        assertEquals(PushCategory.DELIVERY, PushRules.category(mapOf("order_id" to "42")))
        assertNull(PushRules.category(mapOf("category" to "unknown", "order_id" to "42")))
        assertFalse(PushRules.shouldNotify(false, true, true))
        assertFalse(PushRules.shouldNotify(true, false, true))
        assertFalse(PushRules.shouldNotify(true, true, false))
        assertTrue(PushRules.shouldNotify(true, true, true))
    }

    @Test fun routesOnlyValidatedIdentifiers() {
        assertEquals(PushRoute("order-123", "offer_9"), PushRules.route(mapOf("order_id" to "order-123", "offer_id" to "offer_9")))
        for (id in listOf("", "../escape", "https://evil.test", "a b", "x\n", "x".repeat(129))) {
            assertNull(PushRules.route(mapOf("order_id" to id)))
        }
        assertNull(PushRules.route(mapOf("offer_id" to "orphan")))
        assertEquals(PushRoute("42", null), PushRules.route(mapOf("order_id" to "42", "offer_id" to "../bad")))
    }
}
