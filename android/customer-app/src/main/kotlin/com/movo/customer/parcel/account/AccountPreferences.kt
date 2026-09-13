package com.movo.customer.parcel.account

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.accountPrefs by preferencesDataStore("parcel_account_options")
data class AccountOptions(val traffic: Boolean = false, val preferMessages: Boolean = false, val shareLocation: Boolean = false, val contacts: Set<String> = emptySet())
class AccountPreferences(context: Context) {
    private val store = context.applicationContext.accountPrefs
    val options = store.data.map { AccountOptions(it[booleanPreferencesKey("traffic")] ?: false, it[booleanPreferencesKey("messages")] ?: false, false, it[stringSetPreferencesKey("trusted_contacts")] ?: emptySet()) }
    suspend fun traffic(enabled: Boolean) { store.edit { it[booleanPreferencesKey("traffic")] = enabled } }
    suspend fun preferMessages(enabled: Boolean) { store.edit { it[booleanPreferencesKey("messages")] = enabled } }
    suspend fun addContact(name: String, phone: String) { require(AccountRules.validContact(name, phone)); store.edit { it[stringSetPreferencesKey("trusted_contacts")] = (it[stringSetPreferencesKey("trusted_contacts")] ?: emptySet()) + "$name|$phone" } }
    suspend fun deleteContact(contact: String) { store.edit { it[stringSetPreferencesKey("trusted_contacts")] = (it[stringSetPreferencesKey("trusted_contacts")] ?: emptySet()) - contact } }
}
