package com.movo.customer.parcel.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class BrandContrastTest {
    @Test fun `green text meets AA on background and surface`() {
        assertTrue(contrastRatio(0x1FAE59, 0x0E1412) >= 4.5)
        assertTrue(contrastRatio(0x1FAE59, 0x182019) >= 4.5)
    }
    @Test fun `white on brand green requires large bold button text`() {
        assertTrue(contrastRatio(0xFFFFFF, 0x1FAE59) < 3.0)
        assertTrue(contrastRatio(0xFFFFFF, 0x16874A) >= 4.5)
    }
    @Test fun `same color contrast is one`() { assertEquals(1.0, contrastRatio(0x182019, 0x182019)) }
}
