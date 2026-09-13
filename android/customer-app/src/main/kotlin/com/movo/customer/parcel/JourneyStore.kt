package com.movo.customer.parcel

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.movo.customer.parcel.domain.ParcelDraft
import com.movo.customer.parcel.domain.ParcelPlace
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.journeyStore by preferencesDataStore("parcel_journey_v1")

/** Local drafts are not automatically submitted on reconnect. The customer always confirms. */
class JourneyStore(context: Context) {
    private val store = context.applicationContext.journeyStore
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ParcelDraft::class.java)
    private val placeAdapter = moshi.adapter(ParcelPlace::class.java)
    private val draftKey = stringPreferencesKey("draft")
    private val requestKey = stringPreferencesKey("idempotency_key")
    private val orderKey = stringPreferencesKey("active_order")
    private val onboardingKey = booleanPreferencesKey("onboarding_complete")
    private val locationKey = stringPreferencesKey("last_location")
    suspend fun onboardingComplete() = store.data.first()[onboardingKey] ?: false
    suspend fun completeOnboarding() { store.edit { it[onboardingKey] = true } }
    suspend fun draft(): ParcelDraft = store.data.first()[draftKey]?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: ParcelDraft(paymentMethod = "")
    suspend fun saveDraft(draft: ParcelDraft) { store.edit { it[draftKey] = adapter.toJson(draft) } }
    suspend fun requestId(): String {
        var id = ""
        store.edit { p -> id = p[requestKey] ?: UUID.randomUUID().toString(); p[requestKey] = id }
        return id
    }
    suspend fun activeOrder(): String? = store.data.first()[orderKey]
    suspend fun setOrder(id: String) { store.edit { it[orderKey] = id } }
    suspend fun clearOrder() { store.edit { it.remove(orderKey) } }
    suspend fun reset() { store.edit { it.remove(draftKey); it.remove(requestKey); it.remove(orderKey) } }
    suspend fun lastLocation(): ParcelPlace? = store.data.first()[locationKey]?.let { runCatching { placeAdapter.fromJson(it) }.getOrNull() }
    suspend fun setLocation(place: ParcelPlace) { store.edit { it[locationKey] = placeAdapter.toJson(place) } }
}
