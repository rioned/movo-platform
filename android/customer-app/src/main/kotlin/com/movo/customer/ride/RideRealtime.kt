package com.movo.customer.ride

import android.os.Handler
import android.os.Looper
import com.movo.customer.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/** Live ride tracking over the same Socket.IO server, mirroring [com.movo.customer.realtime.CustomerRealtime]. */
class RideRealtime(
    token: String,
    private val onReconnect: () -> Unit,
    private val onUpdate: (String) -> Unit,
    private val onConnection: (Boolean) -> Unit = {}
) {
    companion object { private const val EVENT_RECONNECT = "reconnect" }
    private val main = Handler(Looper.getMainLooper())
    private var rideId: String? = null
    private val authToken = token
    private val socket: Socket = IO.socket(BuildConfig.API_BASE_URL, IO.Options.builder().setReconnection(true).build())
    private fun ui(block: () -> Unit) = main.post(block)

    init {
        socket.on(Socket.EVENT_CONNECT) { socket.emit("authenticate", authToken) }
        socket.on("authenticated") { ui { onConnection(true) }; rideId?.let(::subscribe) }
        socket.on("authentication_error") { ui { onConnection(false) } }
        socket.on(Socket.EVENT_DISCONNECT) { ui { onConnection(false) } }
        socket.io().on(EVENT_RECONNECT) { ui(onReconnect) }
        socket.on("ride_update") { ui { onUpdate("ride_update") } }
        socket.on("driver_location") { ui { onUpdate("driver_location") } }
        socket.on("notification") { ui { onUpdate("notification") } }
        socket.connect()
    }

    fun subscribe(rideId: String) {
        this.rideId = rideId
        if (socket.connected()) socket.emit("subscribe_ride", JSONObject().put("ride_id", rideId))
    }
    fun disconnect() { socket.off(); socket.io().off(); socket.disconnect(); socket.close() }
}
