package com.movo.rider.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Rider credentials and the offline mutation queue live in encrypted storage:
 * a rider's phone is used on the street and may be lost or stolen.
 */
class RiderSession(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "movo_rider_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun token(): String? = preferences.getString(KEY_TOKEN, null)
    fun saveToken(token: String) = preferences.edit().putString(KEY_TOKEN, token).apply()
    fun clear() = preferences.edit().clear().apply()

    fun pendingMutations(): String = preferences.getString(KEY_PENDING, "[]") ?: "[]"
    fun savePendingMutations(payload: String) = preferences.edit().putString(KEY_PENDING, payload).apply()

    fun lastKnownName(): String? = preferences.getString(KEY_NAME, null)
    fun saveName(name: String) = preferences.edit().putString(KEY_NAME, name).apply()

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_PENDING = "pending_mutations"
        const val KEY_NAME = "rider_name"
    }
}
