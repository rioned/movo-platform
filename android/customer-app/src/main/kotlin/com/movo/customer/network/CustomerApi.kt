package com.movo.customer.network

import com.movo.customer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CustomerApiException(val status: Int, val code: String?, override val message: String) : Exception(message)

class CustomerApi(private val tokenProvider: () -> String?) {
    suspend fun get(path: String): JSONObject = request("GET", path, null, null)
    suspend fun post(path: String, body: JSONObject, idempotencyKey: String? = null): JSONObject = request("POST", path, body, idempotencyKey)
    suspend fun put(path: String, body: JSONObject, idempotencyKey: String? = null): JSONObject = request("PUT", path, body, idempotencyKey)

    private suspend fun request(method: String, path: String, payload: JSONObject?, idempotencyKey: String?): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("Content-Type", "application/json")
            tokenProvider()?.takeIf(String::isNotBlank)?.let { setRequestProperty("Authorization", "Bearer $it") }
            idempotencyKey?.let { setRequestProperty("Idempotency-Key", it) }
            if (payload != null) { doOutput = true; outputStream.bufferedWriter().use { it.write(payload.toString()) } }
        }
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status !in 200..299) {
                val errorValue = json?.opt("error")
                val errorObject = errorValue as? JSONObject
                val message = when (errorValue) {
                    is String -> json?.optString("error").orEmpty()
                    is JSONObject -> errorValue.optString("message")
                    else -> ""
                }.takeIf(String::isNotBlank)?.take(240) ?: "Request failed ($status)"
                val code = errorObject?.optString("code")?.takeIf(String::isNotBlank)
                    ?: json?.optString("code")?.takeIf(String::isNotBlank)
                throw CustomerApiException(status, code, message)
            }
            json ?: throw CustomerApiException(status, "invalid_response", "The service returned an invalid response")
        } finally { connection.disconnect() }
    }
}
