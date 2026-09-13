package com.movo.customer.parcel.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.movo.customer.model.CustomerProfile
import com.movo.customer.parcel.domain.*
import com.movo.customer.session.CustomerSession
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun interface ParcelTransport {
    suspend fun request(method: String, path: String, body: JsonObject?, key: String?): Any
}
internal interface ParcelSessionStore {
    var token: String?
    var profile: ParcelProfile?
    fun clear()
}
internal interface ParcelPreferences {
    val settings: Flow<ParcelSettings>
    suspend fun update(value: ParcelSettings)
    suspend fun cache(addresses: List<SavedParcelAddress>) {}
    suspend fun cached(): List<SavedParcelAddress> = emptyList()
    suspend fun cacheRecipients(recipients: List<SavedRecipient>) {}
    suspend fun cachedRecipients(): List<SavedRecipient> = emptyList()
}
private interface ParcelRest {
    @GET suspend fun get(@Url path: String): Map<String, @JvmSuppressWildcards Any?>
    @POST suspend fun post(@Url path: String, @Body body: Map<String, @JvmSuppressWildcards Any?>, @Header("Idempotency-Key") key: String?): Map<String, @JvmSuppressWildcards Any?>
    @PUT suspend fun put(@Url path: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): Map<String, @JvmSuppressWildcards Any?>
    @DELETE suspend fun delete(@Url path: String): Map<String, @JvmSuppressWildcards Any?>
}
private val Context.parcelPreferences by preferencesDataStore("parcel_preferences")
private class DevicePreferences(context: Context, moshi: Moshi) : ParcelPreferences {
    private val store = context.applicationContext.parcelPreferences
    private val adapter = moshi.adapter<List<SavedParcelAddress>>(Types.newParameterizedType(List::class.java, SavedParcelAddress::class.java))
    private val recipientAdapter = moshi.adapter<List<SavedRecipient>>(Types.newParameterizedType(List::class.java, SavedRecipient::class.java))
    override val settings = store.data.map { ParcelSettings(it[booleanPreferencesKey("notifications")] ?: true, true, it[stringPreferencesKey("language")] ?: "en") }
    override suspend fun update(value: ParcelSettings) { store.edit { it[booleanPreferencesKey("notifications")] = value.notificationsEnabled; it[stringPreferencesKey("language")] = value.language } }
    override suspend fun cache(addresses: List<SavedParcelAddress>) { store.edit { it[stringPreferencesKey("addresses")] = adapter.toJson(addresses) } }
    override suspend fun cached(): List<SavedParcelAddress> = store.data.first()[stringPreferencesKey("addresses")]?.let { runCatching { adapter.fromJson(it) }.getOrNull() }.orEmpty()
    override suspend fun cacheRecipients(recipients: List<SavedRecipient>) { store.edit { it[stringPreferencesKey("recipients")] = recipientAdapter.toJson(recipients) } }
    override suspend fun cachedRecipients(): List<SavedRecipient> = store.data.first()[stringPreferencesKey("recipients")]?.let { runCatching { recipientAdapter.fromJson(it) }.getOrNull() }.orEmpty()
}

class LiveParcelRepository internal constructor(private val transport: ParcelTransport, private val session: ParcelSessionStore, private val preferences: ParcelPreferences) : ParcelRepository {
    override val isDemo = false
    private val current = MutableStateFlow(session.profile)
    override val profile = current.asStateFlow()
    override val settings = preferences.settings
    private suspend fun call(method: String, path: String, body: JsonObject? = null, key: String? = null): Any {
        try { return transport.request(method, path, body, key) }
        catch (e: retrofit2.HttpException) {
            if (e.code() == 401) { session.clear(); current.value = null; throw ParcelException("unauthorized", "Your session expired. Sign in again.") }
            val message = runCatching { val raw = e.response()?.errorBody()?.string(); org.json.JSONObject(raw.orEmpty()).optString("error").take(240) }.getOrNull()
            throw ParcelException("http_${e.code()}", message?.takeIf { it.isNotBlank() } ?: "Request failed (${e.code()}). Retry.")
        }
    }
    override suspend fun requestOtp(phone: String, name: String?): Result<OtpChallenge> = parcelResult {
        val path = if (name != null) "api/auth/register" else "api/auth/login"
        val body = if (name != null) mapOf("phone" to phone, "full_name" to name, "role" to "customer") else mapOf("phone" to phone)
        val data = call("POST", path, body).objectValue()
        OtpChallenge(phone, data.text("message") ?: "Verification code requested")
    }
    override suspend fun verifyOtp(phone: String, code: String): Result<ParcelProfile> = parcelResult {
        require(code.matches(Regex("[0-9]{6}"))) { "Enter six digits" }
        val data = call("POST", "api/auth/verify-otp", mapOf("phone" to phone, "otp" to code)).objectValue()
        val p = ParcelWire.profile(data["user"].objectValue())
        session.token = data.required("token"); session.profile = p; current.value = p; p
    }
    override suspend fun refreshProfile(): Result<ParcelProfile> = parcelResult {
        ParcelWire.profile(call("GET", "api/auth/me").objectValue()).also { session.profile = it; current.value = it }
    }
    override suspend fun updateProfile(name: String, email: String?): Result<ParcelProfile> = parcelResult {
        call("PUT", "api/profile", mapOf("full_name" to name, "email" to email)); refreshProfile().getOrThrow()
    }
    override suspend fun signOut(): Result<Unit> = parcelResult { session.clear(); current.value = null; preferences.cache(emptyList()) }
    override suspend fun searchPlaces(query: String): Result<List<ParcelPlace>> = Result.failure(ParcelException("unsupported", "Use Google Places search; no backend place-search endpoint exists."))
    override suspend fun estimate(draft: ParcelDraft): Result<ParcelEstimate> = parcelResult { ParcelWire.estimate(call("POST", "api/deliveries/price", ParcelWire.quoteBody(draft)).objectValue()) }
    override suspend fun create(draft: ParcelDraft, idempotencyKey: String): Result<ParcelDelivery> = parcelResult {
        require(draft.paymentMethod == "cash") { "Mobile money provider checkout is not implemented on the current backend. Choose Cash." }
        val data = call("POST", "api/deliveries", ParcelWire.booking(draft), idempotencyKey).objectValue()
        ParcelWire.delivery(data["delivery"].objectValue())
    }
    override suspend fun history(): Result<List<ParcelDelivery>> = parcelResult {
        val data = call("GET", "api/mobile/v1/customer/deliveries?role=all").objectValue()
        (data["deliveries"] as? List<*>)?.map { ParcelWire.delivery(it.objectValue()) } ?: emptyList()
    }
    override suspend fun detail(id: String): Result<ParcelDelivery> = parcelResult { ParcelWire.delivery(call("GET", "api/deliveries/${safeId(id)}").objectValue()["delivery"].objectValue()) }
    override suspend fun track(id: String): Result<ParcelTracking> = parcelResult {
        val data = call("GET", "api/deliveries/${safeId(id)}/track").objectValue()
        val rider = data["rider"]?.objectValue()
        val delivery = ParcelWire.delivery(data["delivery"].objectValue()).copy(riderName = rider?.text("full_name"), riderPhone = rider?.text("phone"), riderPlate = rider?.text("motorcycle_plate"), riderRating = rider?.number("avg_rating"))
        val location = data["riderLocation"]?.objectValue()
        val events = (data["events"] as? List<*>)?.map { val e = it.objectValue(); ParcelEvent(e.text("status") ?: "", e.text("note") ?: "", e.text("created_at") ?: "") }.orEmpty()
        ParcelTracking(delivery, events, location?.number("lat"), location?.number("lng"), location?.text("created_at"))
    }
    override suspend fun cancel(id: String, reason: String): Result<Unit> = parcelResult { call("PUT", "api/deliveries/${safeId(id)}/cancel", mapOf("reason" to reason)); Unit }
    override suspend fun rate(id: String, score: Int, review: String): Result<Unit> = parcelResult { require(score in 1..5); call("POST", "api/ratings", mapOf("delivery_id" to id, "score" to score, "review" to review)); Unit }
    override suspend fun savedAddresses(): Result<List<SavedParcelAddress>> = parcelResult {
        try {
            val data = call("GET", "api/addresses") as? List<*> ?: emptyList<Any>()
            val addresses = data.mapNotNull { item ->
                val a = item.objectValue(); val lat = a.number("lat"); val lng = a.number("lng")
                if (lat == null || lng == null) null else SavedParcelAddress(a.required("id"), a.required("label"), ParcelPlace(a.required("id"), a.required("label"), a.required("address"), lat, lng), a.text("contact_name") ?: "", a.text("contact_phone") ?: "", a.number("is_default") == 1.0)
            }
            preferences.cache(addresses); addresses
        } catch (e: IOException) { preferences.cached().takeIf { it.isNotEmpty() } ?: throw e }
    }
    override suspend fun saveAddress(address: SavedParcelAddress): Result<SavedParcelAddress> = parcelResult {
        require(address.id.isBlank()) { "The current API supports add and delete, not editing an existing address." }
        val p = address.place
        val response = call("POST", "api/addresses", mapOf("label" to address.label,"address" to p.address,"lat" to p.latitude,"lng" to p.longitude,"contact_name" to address.contactName,"contact_phone" to address.contactPhone,"is_default" to address.isDefault)).objectValue()
        address.copy(id = response.required("id"))
    }
    override suspend fun deleteAddress(id: String): Result<Unit> = parcelResult { call("DELETE", "api/addresses/${safeId(id)}"); preferences.cache(preferences.cached().filterNot { it.id == id }); Unit }
    override suspend fun updateSettings(settings: ParcelSettings): Result<Unit> = parcelResult { preferences.update(settings) }
    // Recipient details ("who receives it" — e.g. someone at home receiving forgotten
    // keys) has no backend contract. It is stored device-only, same pattern as the
    // address cache, and never sent to the server except as recipientName/recipientPhone
    // on a booking draft when the customer picks one.
    override suspend fun savedRecipients(): Result<List<SavedRecipient>> = parcelResult { preferences.cachedRecipients() }
    override suspend fun saveRecipient(recipient: SavedRecipient): Result<SavedRecipient> = parcelResult {
        require(recipient.name.isNotBlank() && recipient.name.length <= 100) { "Recipient name required" }
        require(recipient.phone.matches(Regex("\\+?[0-9]{9,15}"))) { "Valid recipient phone required" }
        require(recipient.note.length <= 300) { "Note is too long" }
        val existing = preferences.cachedRecipients()
        val saved = recipient.copy(id = recipient.id.ifBlank { java.util.UUID.randomUUID().toString() })
        preferences.cacheRecipients(existing.filterNot { it.id == saved.id } + saved)
        saved
    }
    override suspend fun deleteRecipient(id: String): Result<Unit> = parcelResult { preferences.cacheRecipients(preferences.cachedRecipients().filterNot { it.id == id }); Unit }
    private fun safeId(id: String): String { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))); return id }

    companion object {
        /** Fixed origin; never logs credentials or follows redirects carrying bearer headers. */
        fun create(context: Context): LiveParcelRepository {
            val encrypted = CustomerSession(context)
            val session = object : ParcelSessionStore {
                override var token: String? = encrypted.token()
                    set(value) { field = value }
                override var profile: ParcelProfile? = encrypted.profile()?.takeIf { it.role == "customer" }?.let { ParcelProfile(it.id, it.name, it.phone, it.email) }
                    set(value) { field = value; if (value != null && token != null) encrypted.save(token!!, CustomerProfile(value.id, value.name, value.phone, value.email, "customer")) }
                override fun clear() { token = null; profile = null; encrypted.clear() }
            }
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder().header("Accept", "application/json")
                    session.token?.let { request.header("Authorization", "Bearer $it") }
                    chain.proceed(request.build())
                }.build()
            val api = Retrofit.Builder().baseUrl("https://movo-vervice.tech/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(ParcelRest::class.java)
            val transport = ParcelTransport { method, path, body, key -> withContext(Dispatchers.IO) {
                ParcelWire.unwrap(when (method) { "POST" -> api.post(path, body.orEmpty(), key); "PUT" -> api.put(path, body.orEmpty()); "DELETE" -> api.delete(path); else -> api.get(path) })
            } }
            return LiveParcelRepository(transport, session, DevicePreferences(context, moshi))
        }
    }
}
