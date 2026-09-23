package com.movo.customer.location

import kotlinx.coroutines.flow.Flow

data class CustomerFix(val latitude: Double, val longitude: Double, val accuracy: Double?, val recordedAtMillis: Long)
data class CustomerZone(val id: String?, val name: String?, val inServiceArea: Boolean)

/** One foreground collection; its caller owns lifecycle cancellation. */
class CustomerLiveLocation(
    private val upload: suspend (CustomerFix) -> CustomerZone,
    private val onLocation: (CustomerFix, CustomerZone) -> Unit,
    private val onError: (String) -> Unit
) {
    suspend fun track(fixes: Flow<CustomerFix>) {
        fixes.collect { fix ->
            try { onLocation(fix, upload(fix)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { onError(error.message ?: "Location sync unavailable") }
        }
    }
}
