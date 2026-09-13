package com.movo.customer.parcel.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthRulesTest {
    @Test fun `local Rwanda number becomes canonical E164`() { assertEquals("+250788123456", rwandaPhone("0788123456")) }
    @Test fun `foreign and short numbers are rejected`() { assertNull(rwandaPhone("+258841234567")); assertNull(rwandaPhone("123")) }
    @Test fun `OTP pasted from SMS is extracted without logging it`() { assertEquals("654321", pastedOtp("Your MOVO code: 654321.")) }
    @Test fun `ambiguous OTP is rejected`() { assertNull(pastedOtp("123456 and 654321")) }
}
