package com.movo.design

/**
 * Canonical analytics event names emitted by the customer and rider apps (spec §78).
 * Kept as plain string constants, not an enum, so the backend's event catalog and the
 * client's event names are the same literal strings on both ends.
 */
object AnalyticsEvent {
    const val QUOTE_VIEWED = "quote_viewed"
    const val DELIVERY_CONFIRMED = "delivery_confirmed"
    const val DELIVERY_COMPLETED = "delivery_completed"
    const val RIDE_REQUESTED = "ride_requested"
    const val RIDE_COMPLETED = "ride_completed"
    const val RIDER_WENT_ONLINE = "rider_went_online"
    const val RIDER_WENT_OFFLINE = "rider_went_offline"
    const val OFFER_ACCEPTED = "offer_accepted"
    const val OFFER_DECLINED = "offer_declined"
}

/**
 * Each app supplies its own implementation — usually one that batches events through
 * its existing API client — so this module stays free of any network/BuildConfig
 * dependency. An event is a fire-and-forget signal: logging must never throw or block
 * the caller's own flow.
 */
interface AnalyticsLogger {
    fun log(name: String, properties: Map<String, String> = emptyMap())
}

/** Default no-op logger for previews, tests, or callers with nothing wired yet. */
object NoOpAnalyticsLogger : AnalyticsLogger {
    override fun log(name: String, properties: Map<String, String>) {}
}
