package com.movo.customer.parcel.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryRulesTest {
    @Test fun doneRequiresOneEnabledPayment() {
        val options = listOf(PaymentOption("cash", "Cash"), PaymentOption("mtn", "MTN", enabled = false))
        assertFalse(canConfirmPayment("", options))
        assertFalse(canConfirmPayment("mtn", options))
        assertTrue(canConfirmPayment("cash", options))
    }
    @Test fun recipientRequiresNameAndPlausiblePhone() {
        assertFalse(validRecipient("", "+250788123456"))
        assertFalse(validRecipient("Alice", "abc12345678"))
        assertTrue(validRecipient("Alice", "+250 788 123 456"))
    }
    @Test fun unavailablePaymentCannotReplaceSelection() {
        assertEquals("cash", selectPayment("cash", PaymentOption("card", "Card", enabled = false)))
        assertEquals("wallet", selectPayment("cash", PaymentOption("wallet", "Wallet")))
    }
}
