package com.movo.rider.analytics

import com.movo.design.AnalyticsLogger
import com.movo.rider.network.RiderApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Ships analytics events to POST /api/analytics/events. Fire-and-forget on its own
 * scope: a dropped analytics event is fine, a rider workflow blocked or crashed by
 * a flaky analytics call is not.
 */
class RiderAnalytics(private val api: RiderApi) : AnalyticsLogger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun log(name: String, properties: Map<String, String>) {
        scope.launch {
            runCatching {
                val propsJson = JSONObject()
                properties.forEach { (key, value) -> propsJson.put(key, value) }
                api.post("/api/analytics/events", JSONObject().put("name", name).put("properties", propsJson))
            }
        }
    }
}
