package com.movo.customer.parcel.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountRulesTest {
    @Test fun trustedContactsNeedNameAndDialablePhone() {
        assertTrue(AccountRules.validContact("Jane", "+250 788 123 456"))
        assertFalse(AccountRules.validContact("", "+250788123456"))
        assertFalse(AccountRules.validContact("Jane", "https://example.com"))
        assertFalse(AccountRules.validContact("Jane", "112"))
    }
    @Test fun onlyDeliveredOrdersCanBeRatedOnce() {
        assertTrue(AccountRules.canRate("delivered", null))
        assertTrue(AccountRules.canRate("DELIVERED", null))
        assertFalse(AccountRules.canRate("in_transit", null))
        assertFalse(AccountRules.canRate("cancelled", null))
        assertFalse(AccountRules.canRate("delivered", 4))
    }
    @Test fun ratingMustBeBetweenOneAndFive() {
        assertFalse(AccountRules.validRating(0, ""))
        assertFalse(AccountRules.validRating(6, ""))
        assertTrue(AccountRules.validRating(5, "Thanks"))
        assertFalse(AccountRules.validRating(5, "a".repeat(1001)))
    }
    @Test fun supportCannotSendBlankOrOversizedContent() {
        assertTrue(AccountRules.validSupport("delivery", "Missing parcel", "Please investigate"))
        assertFalse(AccountRules.validSupport("unknown", "Help", "Please investigate"))
        assertFalse(AccountRules.validSupport("delivery", " ", "Please investigate"))
        assertFalse(AccountRules.validSupport("delivery", "Help", " "))
    }
    @Test fun profileAcceptsOptionalEmailButRejectsInvalidValues() {
        assertTrue(AccountRules.validProfile("Jane", ""))
        assertTrue(AccountRules.validProfile("Jane", "jane@example.com"))
        assertFalse(AccountRules.validProfile(" ", ""))
        assertFalse(AccountRules.validProfile("Jane", "not an email"))
    }
    @Test fun orderFiltersPreserveUnknownAndCancelledInHistory() {
        assertTrue(AccountRules.matchesOrderFilter("delivered", "completed"))
        assertFalse(AccountRules.matchesOrderFilter("cancelled", "active"))
        assertFalse(AccountRules.matchesOrderFilter("delivered", "active"))
        assertTrue(AccountRules.matchesOrderFilter("in_transit", "active"))
        assertTrue(AccountRules.matchesOrderFilter("unknown", "all"))
        assertFalse(AccountRules.matchesOrderFilter("unknown", "active"))
    }
    @Test fun translationsHaveSafeEnglishFallback() {
        assertEquals("Bonjour", accountText("fr", "Hello", "Muraho", "Bonjour"))
        assertEquals("Muraho", accountText("rw", "Hello", "Muraho", "Bonjour"))
        assertEquals("Hello", accountText("xx", "Hello", "Muraho", "Bonjour"))
    }
    @Test fun addressRequiresLabelAndAddress() {
        assertFalse(AccountRules.validAddress(" ", "Kigali"))
        assertFalse(AccountRules.validAddress("Home", " "))
        assertTrue(AccountRules.validAddress("Home", "KG 7 Ave, Kigali"))
    }
}
