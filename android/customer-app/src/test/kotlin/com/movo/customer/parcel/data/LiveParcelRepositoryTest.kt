package com.movo.customer.parcel.data

import com.movo.customer.parcel.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LiveParcelRepositoryTest {
    @Test fun authUsesRealRoutesPersistsSessionAndUnsupportedSearchDoesNotCallServer() = runTest {
        val calls = mutableListOf<String>()
        val session = object : ParcelSessionStore {
            override var token: String? = null
            override var profile: ParcelProfile? = null
            override fun clear() { token=null; profile=null }
        }
        val preferences = object : ParcelPreferences {
            override val settings = MutableStateFlow(ParcelSettings())
            override suspend fun update(value: ParcelSettings) { settings.value=value }
        }
        val transport = ParcelTransport { method,path,body,key ->
            calls += "$method $path"
            when (path) {
                "api/auth/login" -> mapOf("phone" to body!!["phone"], "message" to "OTP sent")
                "api/auth/verify-otp" -> mapOf("token" to "test-only-token", "user" to mapOf("id" to "1","full_name" to "Alex","phone" to "+250788123456","role" to "customer"))
                else -> error("Unexpected route $path")
            }
        }
        val repo=LiveParcelRepository(transport,session,preferences)
        assertNull(repo.requestOtp("+250788123456").getOrThrow().demoCode)
        repo.verifyOtp("+250788123456","123456").getOrThrow()
        assertEquals("test-only-token",session.token)
        assertEquals("Alex",repo.profile.value?.name)
        assertEquals("unsupported",(repo.searchPlaces("Kigali").exceptionOrNull() as ParcelException).code)
        assertEquals(listOf("POST api/auth/login","POST api/auth/verify-otp"),calls)
        repo.signOut().getOrThrow(); assertNull(session.token)
    }
}
