package com.movo.rider

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

class RiderRealtime(baseUrl: String, token: String, private val onUpdate: () -> Unit) {
  private val socket: Socket = IO.socket(URI(baseUrl))
  init {
    socket.on(Socket.EVENT_CONNECT) { socket.emit("authenticate", token) }
    socket.on("new_delivery") { onUpdate() }
    socket.on("delivery_update") { onUpdate() }
    socket.on("notification") { onUpdate() }
  }
  fun connect() = socket.connect()
  fun disconnect() = socket.disconnect()
  fun sendLocation(lat: Double, lng: Double, deliveryId: String?) {
    socket.emit("rider_location", JSONObject().put("lat", lat).put("lng", lng).put("delivery_id", deliveryId))
  }
}
