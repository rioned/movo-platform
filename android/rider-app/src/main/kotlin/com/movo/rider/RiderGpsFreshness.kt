package com.movo.rider

/** Measurement age, never upload age. Keep stricter than backend dispatch TTL. */
internal object RiderGpsFreshness {
    const val MAX_AGE_MS = 60_000L

    fun shouldPublish(fixElapsedMs: Long, lastElapsedMs: Long?, distanceM: Float, minDistanceM: Float, intervalMs: Long): Boolean =
        lastElapsedMs == null || (fixElapsedMs > lastElapsedMs &&
            (distanceM >= minDistanceM || fixElapsedMs - lastElapsedMs >= intervalMs))

    fun isFresh(recordedAtMs: Long, fixElapsedMs: Long, nowMs: Long, nowElapsedMs: Long): Boolean =
        recordedAtMs > 0 && fixElapsedMs > 0 &&
            nowMs - recordedAtMs in 0..MAX_AGE_MS &&
            nowElapsedMs - fixElapsedMs in 0..MAX_AGE_MS
}
