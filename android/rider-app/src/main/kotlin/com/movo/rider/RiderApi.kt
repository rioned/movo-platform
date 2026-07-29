package com.movo.rider

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class RiderApi(private val context: Context) {
  private val prefs = context.getSharedPreferences("movo_rider", Context.MODE_PRIVATE)
  fun token() = prefs.getString("token", null)
  fun saveToken(token: String) = prefs.edit().putString("token", token).apply()
  fun clear() = prefs.edit().clear().apply()
  suspend fun get(path: String): String = request("GET", path, null)
  suspend fun put(path: String, body: String): String = try { request("PUT", path, body) } catch (e: IOException) { enqueueMutation(path, body); "{\"success\":true,\"data\":{\"queued\":true}}" }
  suspend fun post(path: String, body: String): String = request("POST", path, body)
  suspend fun uploadDocument(kind: String, uri: Uri): String = withContext(Dispatchers.IO) {
    val boundary = "----movo-${UUID.randomUUID()}"
    val c = (URL(BuildConfig.API_BASE_URL + "/api/rider/documents").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"; setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary"); token()?.let { setRequestProperty("Authorization", "Bearer $it") }; doInput = true; doOutput = true
    }
    c.outputStream.buffered().use { out ->
      val name = "${kind}.jpg"
      out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$kind\"; filename=\"$name\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
      context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) } ?: throw IllegalStateException("Unable to read selected document")
      out.write("\r\n--$boundary--\r\n".toByteArray())
    }
    val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    if (c.responseCode !in 200..299 || text.contains("\"success\":false")) throw IllegalStateException("Document upload failed")
    text
  }
  suspend fun uploadProof(deliveryId: String, kind: String, uri: Uri): String = withContext(Dispatchers.IO) {
    val boundary = "----movo-${UUID.randomUUID()}"; val c = (URL(BuildConfig.API_BASE_URL + "/api/rider/deliveries/$deliveryId/proof").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary"); token()?.let { setRequestProperty("Authorization", "Bearer $it") }; doInput = true; doOutput = true }
    c.outputStream.buffered().use { out ->
      out.write("--$boundary\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\n$kind\r\n--$boundary\r\nContent-Disposition: form-data; name=\"proof\"; filename=\"proof.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
      context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) } ?: throw IllegalStateException("Unable to read proof image")
      out.write("\r\n--$boundary--\r\n".toByteArray())
    }
    val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    if (c.responseCode !in 200..299 || text.contains("\"success\":false")) throw IllegalStateException("Proof upload failed"); text
  }
  suspend fun syncPending(): Int {
    val pending = JSONArray(prefs.getString("pending_mutations", "[]")); val remaining = JSONArray(); var synced = 0
    for (i in 0 until pending.length()) { val item = pending.getJSONObject(i); try { request("PUT", item.getString("path"), item.getString("body")); synced++ } catch (_: IOException) { remaining.put(item) } catch (_: Exception) { } }
    prefs.edit().putString("pending_mutations", remaining.toString()).apply(); return synced
  }
  private fun enqueueMutation(path: String, body: String) {
    val pending = JSONArray(prefs.getString("pending_mutations", "[]")); pending.put(JSONObject().put("path", path).put("body", body)); prefs.edit().putString("pending_mutations", pending.toString()).apply()
  }
  private suspend fun request(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
    val c = (URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection).apply { requestMethod = method; setRequestProperty("Content-Type", "application/json"); token()?.let { setRequestProperty("Authorization", "Bearer $it") }; doInput = true; if (body != null) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } } }
    val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    if (c.responseCode !in 200..299 || text.contains("\"success\":false")) throw IllegalStateException(text.substringAfter("\"error\":").trim().take(160))
    text
  }
}
