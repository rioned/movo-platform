package com.movo.customer.realtime

import android.os.Handler
import android.os.Looper
import com.movo.customer.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class CustomerRealtime(
    token: String,
    private val onReconnect: () -> Unit,
    private val onUpdate: (String) -> Unit,
    private val onConnection: (Boolean) -> Unit = {}
) {
    companion object { private const val EVENT_RECONNECT = "reconnect" }
    private val main = Handler(Looper.getMainLooper())
    private var deliveryId: String? = null
    private val authToken = token
    private val socket: Socket = IO.socket(BuildConfig.API_BASE_URL, IO.Options.builder().setReconnection(true).build())
    private fun ui(block: () -> Unit) = main.post(block)

    init {
        socket.on(Socket.EVENT_CONNECT) { socket.emit("authenticate", authToken) }
        socket.on("authenticated") { ui { onConnection(true) }; deliveryId?.let(::subscribe) }
        socket.on("authentication_error") { ui { onConnection(false) } }
        socket.on(Socket.EVENT_DISCONNECT) { ui { onConnection(false) } }
        socket.io().on(EVENT_RECONNECT) { ui(onReconnect) }
        socket.on("delivery_update") { ui { onUpdate("delivery_update") } }
        socket.on("rider_location") { ui { onUpdate("rider_location") } }
        socket.on("notification") { ui { onUpdate("notification") } }
        socket.connect()
    }

    fun subscribe(deliveryId: String) {
        this.deliveryId = deliveryId
        if (socket.connected()) socket.emit("subscribe_delivery", JSONObject().put("delivery_id", deliveryId))
    }
    fun disconnect() { socket.off(); socket.io().off(); socket.disconnect(); socket.close() }
}
