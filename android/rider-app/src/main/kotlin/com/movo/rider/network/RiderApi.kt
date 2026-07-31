package com.movo.rider.network

import android.content.Context
import android.net.Uri
import com.movo.rider.BuildConfig
import com.movo.rider.session.RiderSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class RiderApiException(val status: Int, val code: String?, override val message: String) : Exception(message)

/**
 * Rider HTTP client with an offline-first write path.
 *
 * Kigali connectivity drops mid-delivery, and a lost "item collected" event is a
 * dispute. Failed writes are queued in encrypted storage and replayed on the next
 * successful round trip (spec §17: temporary offline operation, auto-sync, retries).
 */
class RiderApi(private val context: Context, private val session: RiderSession = RiderSession(context)) {

    fun token(): String? = session.token()
    fun saveToken(token: String) = session.saveToken(token)
    fun clear() = session.clear()
    val isAuthenticated: Boolean get() = token() != null

    suspend fun get(path: String): JSONObject = json(request("GET", path, null))

    suspend fun post(path: String, body: JSONObject): JSONObject = json(request("POST", path, body.toString()))

    /** PUT is the mutation path; when the network fails the call is queued, not lost. */
    suspend fun put(path: String, body: JSONObject = JSONObject()): JSONObject = try {
        json(request("PUT", path, body.toString()))
    } catch (offline: IOException) {
        enqueue(path, body.toString())
        JSONObject().put("queued", true)
    }

    /** Replays queued mutations; returns how many were accepted by the server. */
    suspend fun syncPending(): Int {
        val pending = JSONArray(session.pendingMutations())
        if (pending.length() == 0) return 0
        val remaining = JSONArray()
        var synced = 0
        for (index in 0 until pending.length()) {
            val item = pending.getJSONObject(index)
            try {
                request("PUT", item.getString("path"), item.getString("body"))
                synced += 1
            } catch (_: IOException) {
                remaining.put(item)
            } catch (_: Exception) {
                // A rejected mutation is dropped: retrying a 4xx forever would wedge the queue.
            }
        }
        session.savePendingMutations(remaining.toString())
        return synced
    }

    fun pendingCount(): Int = runCatching { JSONArray(session.pendingMutations()).length() }.getOrDefault(0)

    suspend fun uploadDocument(kind: String, uri: Uri): JSONObject =
        multipart("/api/rider/documents", listOf(FilePart(kind, "$kind.jpg", uri)), emptyMap())

    suspend fun uploadProof(deliveryId: String, kind: String, uri: Uri): JSONObject =
        multipart("/api/rider/deliveries/$deliveryId/proof", listOf(FilePart("proof", "proof.jpg", uri)), mapOf("kind" to kind))

    private data class FilePart(val field: String, val filename: String, val uri: Uri)

    private suspend fun multipart(path: String, files: List<FilePart>, fields: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val boundary = "----movo-${UUID.randomUUID()}"
            val connection = open(path, "POST").apply {
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
            }
            connection.outputStream.buffered().use { out ->
                for ((name, value) in fields) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                for (file in files) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"${file.field}\"; filename=\"${file.filename}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                    context.contentResolver.openInputStream(file.uri)?.use { it.copyTo(out) }
                        ?: throw IllegalStateException("Unable to read the selected image")
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary--\r\n".toByteArray())
            }
            json(readResponse(connection))
        }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            token()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private suspend fun request(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val connection = open(path, method).apply {
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String = try {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val payload = runCatching { JSONObject(text) }.getOrNull()
            throw RiderApiException(
                status,
                payload?.optString("code")?.takeIf(String::isNotBlank),
                payload?.optString("error")?.takeIf(String::isNotBlank)?.take(240) ?: "Request failed ($status)"
            )
        }
        text
    } finally {
        connection.disconnect()
    }

    private fun json(text: String): JSONObject {
        val payload = runCatching { JSONObject(text) }.getOrNull()
            ?: throw RiderApiException(0, "invalid_response", "The service returned an invalid response")
        return payload.optJSONObject("data") ?: payload
    }

    private fun enqueue(path: String, body: String) {
        val pending = runCatching { JSONArray(session.pendingMutations()) }.getOrDefault(JSONArray())
        pending.put(JSONObject().put("path", path).put("body", body))
        session.savePendingMutations(pending.toString())
    }
}
