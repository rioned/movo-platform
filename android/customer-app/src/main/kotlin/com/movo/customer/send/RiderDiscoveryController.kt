package com.movo.customer.send

import com.movo.customer.dataObject
import com.movo.customer.model.Coordinate
import com.movo.customer.network.CustomerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_ERROR_MESSAGE_LENGTH = 240

/** Reports how many eligible riders MOVO can see near a pickup — never which ones (spec §12: blind dispatch). */
fun interface NearbyRiderSource {
    suspend fun scan(pickup: Coordinate): Int
}

/** Thrown by a [NearbyRiderSource] when the backend reports the pickup has no service zone at all. */
class OutOfServiceAreaException(message: String) : Exception(message)

class RiderDiscoveryController(private val source: NearbyRiderSource) {
    private val mutableSnapshot = MutableStateFlow(DiscoverySnapshot(DiscoveryPhase.Locating))
    val snapshot: StateFlow<DiscoverySnapshot> = mutableSnapshot.asStateFlow()

    private val stateLock = Any()
    private var requestVersion = 0L
    private var inFlightPickup: Coordinate? = null

    fun invalidate(pickup: Coordinate?) {
        synchronized(stateLock) {
            requestVersion += 1
            inFlightPickup = null
            mutableSnapshot.value = mutableSnapshot.value.invalidateForPickup(pickup)
        }
    }

    suspend fun scan(pickup: Coordinate, online: Boolean) {
        if (!pickup.isFinite) {
            invalidate(pickup)
            return
        }
        if (!online) {
            synchronized(stateLock) {
                requestVersion += 1
                inFlightPickup = null
                mutableSnapshot.value = DiscoverySnapshot(DiscoveryPhase.Offline, pickup)
            }
            return
        }

        val version = synchronized(stateLock) {
            if (inFlightPickup == pickup) return
            requestVersion += 1
            inFlightPickup = pickup
            mutableSnapshot.value = DiscoverySnapshot(DiscoveryPhase.Scanning, pickup)
            requestVersion
        }

        val count = try {
            source.scan(pickup)
        } catch (error: Exception) {
            if (error is CancellationException) {
                synchronized(stateLock) {
                    if (isCurrentLocked(version, pickup)) inFlightPickup = null
                }
                throw error
            }
            if (error is OutOfServiceAreaException) {
                synchronized(stateLock) {
                    if (isCurrentLocked(version, pickup)) {
                        inFlightPickup = null
                        mutableSnapshot.value = DiscoverySnapshot(DiscoveryPhase.OutOfServiceArea, pickup)
                    }
                }
                return
            }
            val message = error.message
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_ERROR_MESSAGE_LENGTH)
                ?: "Unable to scan for riders"
            synchronized(stateLock) {
                if (isCurrentLocked(version, pickup)) {
                    inFlightPickup = null
                    mutableSnapshot.value = DiscoverySnapshot(DiscoveryPhase.Error(message), pickup)
                }
            }
            return
        }

        synchronized(stateLock) {
            if (!isCurrentLocked(version, pickup)) return
            inFlightPickup = null
            mutableSnapshot.value = DiscoverySnapshot(
                phase = if (count <= 0) DiscoveryPhase.NoRiders else DiscoveryPhase.Available,
                pickup = pickup,
                riderCount = count.coerceAtLeast(0)
            )
        }
    }

    private fun isCurrentLocked(version: Long, pickup: Coordinate): Boolean =
        requestVersion == version && inFlightPickup == pickup
}

fun customerNearbyRiderSource(api: CustomerApi, radiusKm: Int = 10): NearbyRiderSource =
    NearbyRiderSource { pickup ->
        val data = api.get(
            "/api/mobile/v1/customer/nearby-riders?lat=${pickup.latitude}&lng=${pickup.longitude}&radius_km=$radiusKm"
        ).dataObject()

        // `in_service_area` defaults to true so an older backend that hasn't shipped
        // this field yet degrades to the previous "empty results" behavior instead
        // of wrongly claiming every pickup is unserviceable.
        if (!data.optBoolean("in_service_area", true)) {
            throw OutOfServiceAreaException("MOVO is not currently available at this location.")
        }
        data.optInt("rider_count", 0)
    }
