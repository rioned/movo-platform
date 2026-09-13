package com.movo.customer.parcel.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ParcelRepository {
    val isDemo: Boolean
    val profile: StateFlow<ParcelProfile?>
    val settings: Flow<ParcelSettings>
    suspend fun requestOtp(phone: String, name: String? = null): Result<OtpChallenge>
    suspend fun verifyOtp(phone: String, code: String): Result<ParcelProfile>
    suspend fun refreshProfile(): Result<ParcelProfile>
    suspend fun updateProfile(name: String, email: String?): Result<ParcelProfile>
    suspend fun signOut(): Result<Unit>
    suspend fun searchPlaces(query: String): Result<List<ParcelPlace>>
    suspend fun estimate(draft: ParcelDraft): Result<ParcelEstimate>
    suspend fun create(draft: ParcelDraft, idempotencyKey: String): Result<ParcelDelivery>
    suspend fun history(): Result<List<ParcelDelivery>>
    suspend fun detail(id: String): Result<ParcelDelivery>
    suspend fun track(id: String): Result<ParcelTracking>
    suspend fun cancel(id: String, reason: String): Result<Unit>
    suspend fun rate(id: String, score: Int, review: String = ""): Result<Unit>
    suspend fun savedAddresses(): Result<List<SavedParcelAddress>>
    suspend fun saveAddress(address: SavedParcelAddress): Result<SavedParcelAddress>
    suspend fun deleteAddress(id: String): Result<Unit>
    suspend fun updateSettings(settings: ParcelSettings): Result<Unit>
    /** Device-only: this backend has no recipient-book endpoint. */
    suspend fun savedRecipients(): Result<List<SavedRecipient>>
    suspend fun saveRecipient(recipient: SavedRecipient): Result<SavedRecipient>
    suspend fun deleteRecipient(id: String): Result<Unit>
}
