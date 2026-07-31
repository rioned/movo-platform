package com.movo.customer.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.movo.customer.model.*
import org.json.JSONObject

class CustomerSession(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val preferences = EncryptedSharedPreferences.create(context, "customer_session", masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun token(): String? = preferences.getString("access_token", null)
    fun profile(): CustomerProfile? {
        if (!preferences.contains("profile_id")) return null
        return CustomerProfile(
            preferences.getString("profile_id", "")!!, preferences.getString("profileName", "Customer")!!,
            preferences.getString("profilePhone", "")!!, preferences.getString("profileEmail", null),
            preferences.getString("profileRole", "")!!
        )
    }
    fun save(token: String, profile: CustomerProfile) {
        preferences.edit().putString("access_token", token).apply(); updateProfile(profile)
    }
    fun updateProfile(profile: CustomerProfile) {
        preferences.edit().putString("profile_id", profile.id).putString("profileName", profile.name)
            .putString("profilePhone", profile.phone).putString("profileEmail", profile.email)
            .putString("profileRole", profile.role).apply()
    }
    fun clear() = preferences.edit().clear().apply()

    fun saveJourney(journey: SendJourney) {
        val draft = journey.draft
        val json = JSONObject()
            .put("pickupLat", draft.pickup?.latitude).put("pickupLng", draft.pickup?.longitude)
            .put("destinationLat", draft.destination?.latitude).put("destinationLng", draft.destination?.longitude)
            .put("pickupAddress", draft.pickupAddress).put("destinationAddress", draft.destinationAddress)
            .put("senderName", draft.senderName).put("senderPhone", draft.senderPhone)
            .put("receiverName", draft.receiverName).put("receiverPhone", draft.receiverPhone)
            .put("itemType", draft.itemType).put("itemDescription", draft.itemDescription)
            .put("deliveryInstructions", draft.deliveryInstructions).put("paymentMethod", draft.paymentMethod)
            .put("quotePrice", journey.quote?.price).put("quoteDistance", journey.quote?.distanceKm)
            .put("quoteEta", journey.quote?.etaMinutes).put("deliveryId", journey.deliveryId)
            .put("creationIdempotencyKey", journey.creationIdempotencyKey)
            .put("replacementIdempotencyKey", journey.replacementIdempotencyKey)
        // apply(), not commit(): this runs on every draft edit and an encrypted
        // synchronous write on the UI thread stutters the whole booking flow.
        preferences.edit().putString("sendJourney", json.toString()).apply()
    }

    fun restoreJourney(): SendJourney? = preferences.getString("sendJourney", null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            fun coordinate(lat: String, lng: String): Coordinate? = if (json.isNull(lat) || json.isNull(lng)) null else Coordinate(json.getDouble(lat), json.getDouble(lng)).takeIf { it.isFinite }
            val draft = SendDraft(
                coordinate("pickupLat", "pickupLng"), coordinate("destinationLat", "destinationLng"),
                json.optString("pickupAddress"), json.optString("destinationAddress"), json.optString("senderName"),
                json.optString("senderPhone"), json.optString("receiverName"), json.optString("receiverPhone"),
                json.optString("itemType", "parcel"), json.optString("itemDescription"),
                json.optString("deliveryInstructions"), json.optString("paymentMethod", "cash")
            )
            val quote = if (json.isNull("quotePrice")) null else Quote(json.getDouble("quotePrice"), json.optDouble("quoteDistance"), json.optInt("quoteEta"))
            SendJourney(draft, quote, json.optString("deliveryId").takeIf(String::isNotBlank),
                json.getString("creationIdempotencyKey"), json.getString("replacementIdempotencyKey"))
        }.getOrNull()
    }
    fun clearJourney() = preferences.edit().remove("sendJourney").apply()
}
