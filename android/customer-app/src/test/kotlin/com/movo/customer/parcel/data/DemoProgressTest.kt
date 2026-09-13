package com.movo.customer.parcel.data
import kotlin.test.*
class DemoProgressTest {
    @Test fun `demo progresses through parcel handover and then stops`() {
        assertEquals("searching", demoStatus(0))
        assertEquals("assigned", demoStatus(10))
        assertEquals("picked_up", demoStatus(25))
        assertEquals("in_transit", demoStatus(40))
        assertEquals("delivered", demoStatus(70))
        assertEquals("delivered", demoStatus(900))
    }
}
