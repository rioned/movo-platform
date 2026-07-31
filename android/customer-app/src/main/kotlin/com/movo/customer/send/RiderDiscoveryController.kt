package com.movo.customer.send

import com.movo.customer.dataObject
import com.movo.customer.model.Coordinate
import com.movo.customer.model.NearbyRider
import com.movo.customer.model.toNearbyRider
import com.movo.customer.network.CustomerApi
import com.movo.design.MovoServiceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_ERROR_MESSAGE_LENGTH = 240

fun interface NearbyRiderSource {
    suspend fun scan(pickup: Coordinate): List<NearbyRider>
}

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

        val riders = try {
            source.scan(pickup).filter { it.location.isFinite }
        } catch (error: Exception) {
            if (error is CancellationException) {
                synchronized(stateLock) {
                    if (isCurrentLocked(version, pickup)) inFlightPickup = null
                }
                throw error
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
                phase = if (riders.isEmpty()) DiscoveryPhase.NoRiders else DiscoveryPhase.Available,
                pickup = pickup,
                riders = riders
            )
        }
    }

    private fun isCurrentLocked(version: Long, pickup: Coordinate): Boolean =
        requestVersion == version && inFlightPickup == pickup
}

/**
 * Live rider availability around a pickup.
 *
 * `mode` narrows the scan to riders who work that product, so a passenger is never
 * shown a rider who has turned rides off and would only have to decline them.
 */
fun customerNearbyRiderSource(
    api: CustomerApi,
    radiusKm: Int = 10,
    mode: MovoServiceMode? = null
): NearbyRiderSource =
    NearbyRiderSource { pickup ->
        val modeQuery = mode?.let { "&mode=${it.apiValue}" }.orEmpty()
        val riders = api.get(
            "/api/mobile/v1/customer/nearby-riders?lat=${pickup.latitude}&lng=${pickup.longitude}&radius_km=$radiusKm$modeQuery"
        )
            .dataObject()
            .optJSONArray("riders")

        parseNearbyRiders(riders)
    }

internal fun parseNearbyRiders(riders: JSONArray?): List<NearbyRider> {
    val objects = buildList {
        for (index in 0 until (riders?.length() ?: 0)) {
            riders?.optJSONObject(index)?.let(::add)
        }
    }
    return mapValidNearbyRiders(
        riders = objects,
        valueAt = { rider, name -> rider.opt(name) },
        mapper = JSONObject::toNearbyRider
    )
}

internal fun <T, R> mapValidNearbyRiders(
    riders: Iterable<T>,
    valueAt: (T, String) -> Any?,
    mapper: (T) -> R
): List<R> = buildList {
    for (rider in riders) {
        val latitude = authoritativeDouble(rider, valueAt, "latitude", "lat", "current_lat") ?: continue
        val longitude = authoritativeDouble(rider, valueAt, "longitude", "lng", "current_lng") ?: continue
        if (!Coordinate(latitude, longitude).isFinite) continue
        add(mapper(rider))
    }
}

private fun <T> authoritativeDouble(
    rider: T,
    valueAt: (T, String) -> Any?,
    vararg names: String
): Double? = names.firstNotNullOfOrNull { name ->
    valueAt(rider, name)
        ?.takeUnless { it == JSONObject.NULL }
        ?.toString()
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
}
