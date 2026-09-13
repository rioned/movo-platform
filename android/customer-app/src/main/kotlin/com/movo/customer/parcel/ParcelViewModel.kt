package com.movo.customer.parcel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movo.customer.parcel.account.AccountNotice
import com.movo.customer.parcel.data.DemoParcelRepository
import com.movo.customer.parcel.data.LiveParcelRepository
import com.movo.customer.parcel.domain.*
import com.movo.customer.parcel.delivery.HybridPlacesSearch
import com.movo.customer.network.CustomerApi
import com.movo.customer.session.CustomerSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

 data class ParcelAppState(
    val ready: Boolean = false, val onboarded: Boolean = false, val demo: Boolean = false,
    val profile: ParcelProfile? = null, val language: String = "en", val draft: ParcelDraft = ParcelDraft(paymentMethod = ""),
    val estimate: ParcelEstimate? = null, val order: ParcelDelivery? = null, val tracking: ParcelTracking? = null,
    val orders: List<ParcelDelivery> = emptyList(), val addresses: List<SavedParcelAddress> = emptyList(),
    val recipients: List<SavedRecipient> = emptyList(),
    val notices: List<AccountNotice> = emptyList(), val results: List<ParcelPlace> = emptyList(),
    val lastLocation: ParcelPlace? = null, val phone: String = "", val challenge: OtpChallenge? = null,
    val resendAt: Long = 0L, val busy: Boolean = false, val searching: Boolean = false, val error: String? = null
)

/** A single customer journey coordinator. Transport/repositories own backend contracts. */
@HiltViewModel
class ParcelViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(ParcelAppState())
    val state = mutable.asStateFlow()
    private val journey = JourneyStore(context)
    private lateinit var repository: ParcelRepository
    private lateinit var legacyApi: CustomerApi
    private var searchJob: Job? = null
    private var settingsJob: Job? = null
    private var draftSaveJob: Job? = null
    private var demoKey = UUID.randomUUID().toString()
    private var lastRetry: (() -> Unit)? = null
    private val places by lazy { HybridPlacesSearch(context) }

    init {
        viewModelScope.launch {
            try {
                repository = withContext(Dispatchers.IO) { LiveParcelRepository.create(context) }
                legacyApi = withContext(Dispatchers.IO) { val session = CustomerSession(context); CustomerApi(session::token) }
                val profile = repository.profile.value
                mutable.update { it.copy(profile = profile, onboarded = journey.onboardingComplete(), draft = journey.draft(), lastLocation = journey.lastLocation()) }
                bindSettings()
                if (profile != null) {
                    repository.refreshProfile().onSuccess { p -> mutable.update { it.copy(profile = p) } }
                    if (repository.profile.value == null) mutable.update { it.copy(profile = null) }
                    if (repository.profile.value != null) {
                        loadHome()
                        journey.activeOrder()?.let { id -> repository.detail(id).onSuccess { order -> mutable.update { it.copy(order = order) } } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Couldn't restore your account. Reopen the app to retry.") } }
            finally { mutable.update { it.copy(ready = true) } }
        }
    }
    private fun bindSettings() {
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch { repository.settings.collect { settings -> mutable.update { it.copy(language = settings.language) } } }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun retry() { dismissError(); lastRetry?.invoke() }
    fun report(message: String) { mutable.update { it.copy(error = message) } }
    private fun action(retry: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        lastRetry = retry
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                mutable.update { it.copy(error = e.message?.take(240) ?: "Connection unavailable. Please retry.", profile = if (::repository.isInitialized) repository.profile.value else it.profile) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun completeOnboarding(onDone: () -> Unit) = action { journey.completeOnboarding(); mutable.update { it.copy(onboarded = true) }; onDone() }
    fun requestOtp(phone: String, name: String?, onDone: () -> Unit) = action {
        val challenge = repository.requestOtp(phone, name).getOrThrow()
        mutable.update { it.copy(phone = phone, challenge = challenge, resendAt = android.os.SystemClock.elapsedRealtime() + 60_000) }
        onDone()
    }
    fun resendOtp() = action {
        if (android.os.SystemClock.elapsedRealtime() < mutable.value.resendAt) return@action
        val challenge = repository.requestOtp(mutable.value.phone).getOrThrow()
        mutable.update { it.copy(challenge = challenge, resendAt = android.os.SystemClock.elapsedRealtime() + 60_000) }
    }
    fun verify(code: String, onDone: () -> Unit) = action {
        val profile = repository.verifyOtp(mutable.value.phone, code).getOrThrow()
        mutable.update { it.copy(profile = profile) }; loadHome(); onDone()
    }
    fun setup(name: String, onDone: () -> Unit) = action {
        val profile = repository.updateProfile(name, mutable.value.profile?.email).getOrThrow()
        mutable.update { it.copy(profile = profile) }; onDone()
    }
    fun enterDemo(onDone: () -> Unit) = action {
        repository = DemoParcelRepository(); bindSettings()
        val phone = "+250788123456"
        val challenge = repository.requestOtp(phone, "Demo sender").getOrThrow()
        val profile = repository.verifyOtp(phone, challenge.demoCode ?: "123456").getOrThrow()
        mutable.value = ParcelAppState(ready = true, onboarded = true, demo = true, profile = profile)
        loadHome(); onDone()
    }
    fun signOut(onDone: () -> Unit) = action {
        repository.signOut().getOrThrow(); draftSaveJob?.cancelAndJoin(); journey.reset()
        withContext(Dispatchers.IO) { CustomerSession(context).clear() }
        repository = withContext(Dispatchers.IO) { LiveParcelRepository.create(context) }; bindSettings()
        mutable.value = ParcelAppState(ready = true, onboarded = true); onDone()
    }
    private suspend fun loadHome() {
        repository.history().onSuccess { orders -> mutable.update { it.copy(orders = orders) } }.onFailure { e -> report(e.message ?: "Could not load orders") }
        repository.savedAddresses().onSuccess { addresses -> mutable.update { it.copy(addresses = addresses) } }
        repository.savedRecipients().onSuccess { recipients -> mutable.update { it.copy(recipients = recipients) } }
    }
    fun refreshHome() = action(retry = ::refreshHome) { loadHome() }
    fun newDelivery(onDone: () -> Unit) = action {
        draftSaveJob?.cancelAndJoin(); if (!state.value.demo) journey.reset(); demoKey = UUID.randomUUID().toString()
        val profile = state.value.profile
        val preferMessages = com.movo.customer.parcel.account.AccountPreferences(context).options.first().preferMessages
        mutable.update { it.copy(draft = ParcelDraft(senderName = profile?.name.orEmpty(), senderPhone = profile?.phone.orEmpty(), paymentMethod = "", instructions = if (preferMessages) "Please message instead of calling unless urgent." else ""), estimate = null, order = null, tracking = null) }
        onDone()
    }
    fun editDraft(draft: ParcelDraft) {
        mutable.update { it.copy(draft = draft, estimate = null) }
        draftSaveJob?.cancel()
        if (!state.value.demo) draftSaveJob = viewModelScope.launch { delay(300); journey.saveDraft(draft) }
    }
    fun setLocation(place: ParcelPlace) { mutable.update { it.copy(lastLocation = place) }; viewModelScope.launch { journey.setLocation(place) } }
    fun validatePlace(place: ParcelPlace, onAccepted: () -> Unit) = action {
        require(place.latitude.isFinite() && place.longitude.isFinite()) { "Invalid map location" }
        if (state.value.demo) {
            require(place.latitude in -2.15..-1.75 && place.longitude in 29.9..30.3) { "Outside demo Kigali service area" }
        } else {
            val response = legacyApi.get("/api/mobile/v1/customer/nearby-riders?lat=${place.latitude}&lng=${place.longitude}")
            check(response.getJSONObject("data").optBoolean("in_service_area", false)) { "Outside service area. Choose an address inside MOVO's Kigali zone." }
        }
        onAccepted()
    }
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { mutable.update { it.copy(results = it.addresses.map { a -> a.place }, searching = false) }; return }
        mutable.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            delay(350)
            val result = if (state.value.demo) repository.searchPlaces(query) else places.search(query)
            result.onSuccess { results -> mutable.update { it.copy(results = results, searching = false) } }
                .onFailure { failure -> mutable.update { it.copy(results = emptyList(), searching = false, error = failure.message) } }
        }
    }
    fun quote() = action(retry = ::quote) {
        val draft = state.value.draft
        val estimate = repository.estimate(draft).getOrThrow()
        if (state.value.draft == draft) mutable.update { it.copy(estimate = estimate) }
    }
    fun confirm(onDone: () -> Unit) = action {
        check(state.value.estimate != null) { "Refresh your quote before confirming." }
        state.value.order?.takeIf { it.isActive }?.let { onDone(); return@action }
        val draft = state.value.draft
        draftSaveJob?.cancelAndJoin()
        val id = if (state.value.demo) demoKey else { journey.saveDraft(draft); journey.requestId() }
        val delivery = repository.create(draft, id).getOrThrow()
        if (!state.value.demo) journey.setOrder(delivery.id)
        mutable.update { it.copy(order = delivery, tracking = null) }; onDone()
    }
    suspend fun pollTracking() {
        val id = state.value.order?.id ?: return
        repository.track(id).onSuccess { tracking ->
            if (state.value.order?.id == id) mutable.update { it.copy(order = tracking.delivery, tracking = tracking, error = null) }
        }.onFailure { error -> mutable.update { it.copy(profile = repository.profile.value, error = error.message ?: "Tracking is temporarily unavailable. Retry.") } }
    }
    fun refreshTracking() = action(retry = ::refreshTracking) { pollTracking() }
    fun openOrder(id: String, onDone: () -> Unit) = action {
        val order = repository.detail(id).getOrThrow()
        mutable.update { it.copy(order = order, tracking = null) }; onDone()
    }
    fun cancel(onDone: () -> Unit) = action {
        val order = state.value.order ?: return@action
        check(order.canCancel) { "This delivery can no longer be cancelled. Contact support." }
        repository.cancel(order.id, "Cancelled by sender").getOrThrow()
        mutable.update { it.copy(order = order.copy(status = "cancelled")) }
        if (!state.value.demo) journey.clearOrder(); loadHome(); onDone()
    }
    fun repeatOrder(onDone: () -> Unit) {
        val order = state.value.order ?: return
        newDelivery {
            editDraft(state.value.draft.copy(pickup = order.pickup, destination = order.destination, recipientName = order.recipientName, recipientPhone = order.recipientPhone, description = order.description))
            onDone()
        }
    }
    suspend fun updateProfile(name: String, email: String): Result<Unit> = repository.updateProfile(name, email.takeIf { it.isNotBlank() }).map { profile -> mutable.update { it.copy(profile = profile) } }
    suspend fun setLanguage(language: String): Result<Unit> = repository.updateSettings(repository.settings.first().copy(language = language))
    suspend fun rate(score: Int, review: String): Result<Unit> {
        val id = state.value.order?.id ?: return Result.failure(IllegalStateException("Select a delivery first"))
        return repository.rate(id, score, review).onSuccess { mutable.update { it.copy(order = it.order?.copy(rating = score)) } }
    }
    suspend fun saveAddress(address: SavedParcelAddress): Result<Unit> = repository.saveAddress(address).map { loadHome() }
    suspend fun deleteAddress(id: String): Result<Unit> = repository.deleteAddress(id).map { loadHome() }
    suspend fun saveRecipient(recipient: SavedRecipient): Result<Unit> = repository.saveRecipient(recipient).map { loadHome() }
    suspend fun deleteRecipient(id: String): Result<Unit> = repository.deleteRecipient(id).map { loadHome() }
    suspend fun ticket(category: String, subject: String, description: String): Result<Unit> = parcelResult {
        if (state.value.demo) throw ParcelException("demo_only", "Demo mode does not submit real support tickets.")
        val order = state.value.order?.id
        legacyApi.post("/api/tickets", JSONObject().put("category", category).put("subject", subject).put("description", listOfNotNull(order?.let { "Delivery: $it" }, description).joinToString("\n")).put("priority", "normal"))
        Unit
    }
    fun notifications() = action(retry = ::notifications) {
        if (state.value.demo) { mutable.update { it.copy(notices = emptyList()) }; return@action }
        val response = legacyApi.get("/api/notifications")
        val data = response.optJSONArray("data") ?: org.json.JSONArray()
        val notices = (0 until data.length()).map { i -> val n = data.getJSONObject(i); AccountNotice(n.optString("id"), n.optString("title"), n.optString("body"), n.optString("created_at"), n.optInt("is_read") == 1) }
        mutable.update { it.copy(notices = notices) }
    }
    suspend fun markRead(id: String): Result<Unit> = parcelResult {
        check(!state.value.demo) { "No live notifications in demo mode" }
        legacyApi.put("/api/notifications/$id/read", JSONObject())
        mutable.update { it.copy(notices = it.notices.map { n -> if (n.id == id) n.copy(read = true) else n }) }
    }
}
