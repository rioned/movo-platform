package com.movo.customer.parcel.push

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.parcelPushStore by preferencesDataStore(name = "parcel_push")

/** Shared instance of DataStore via the context delegate; safe for settings UI and FCM service. */
class PushPreferences(context: Context) {
    private val store = context.applicationContext.parcelPushStore
    val categories: Flow<Map<PushCategory, Boolean>> = store.data.map { prefs ->
        PushCategory.entries.associateWith { prefs[booleanPreferencesKey(it.key)] ?: defaultEnabled(it) }
    }
    suspend fun setEnabled(category: PushCategory, enabled: Boolean) {
        store.edit { it[booleanPreferencesKey(category.key)] = enabled }
    }
    suspend fun isEnabled(category: PushCategory) = categories.first().getValue(category)

    /** Persist bounded hashes only, not notification content. Atomic across service recreations. */
    internal suspend fun claim(eventKey: String): Boolean {
        var accepted = false
        store.edit { prefs ->
            val recent = prefs[recentKey].orEmpty().split('\n').filter { it.isNotEmpty() }
            if (eventKey !in recent) {
                prefs[recentKey] = (recent + eventKey).takeLast(128).joinToString("\n")
                accepted = true
            }
        }
        return accepted
    }
    companion object {
        private val recentKey = stringPreferencesKey("recent_event_hashes")
        private fun defaultEnabled(category: PushCategory) = category != PushCategory.PROMOTIONS
    }
}
