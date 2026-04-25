package com.driveplayer.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.driveplayer.ui.cloud.SavedAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "accounts")

private const val ACCOUNTS_KEY = "saved_accounts"

class AccountPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val savedAccounts: Flow<List<SavedAccount>> = context.accountDataStore.data.map { preferences ->
        val accountsJson = preferences[stringPreferencesKey(ACCOUNTS_KEY)]
        if (accountsJson != null) {
            try {
                json.decodeFromString<List<SavedAccount>>(accountsJson)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    suspend fun saveAccount(account: SavedAccount) {
        context.accountDataStore.edit { preferences ->
            val currentAccounts = getCurrentAccounts(preferences)
            val updatedAccounts = if (currentAccounts.any { it.email == account.email }) {
                currentAccounts
            } else {
                currentAccounts + account
            }
            preferences[stringPreferencesKey(ACCOUNTS_KEY)] = json.encodeToString(updatedAccounts)
        }
    }

    suspend fun removeAccount(email: String) {
        context.accountDataStore.edit { preferences ->
            val currentAccounts = getCurrentAccounts(preferences)
            val updatedAccounts = currentAccounts.filter { it.email != email }
            preferences[stringPreferencesKey(ACCOUNTS_KEY)] = json.encodeToString(updatedAccounts)
        }
    }

    suspend fun clearAllAccounts() {
        context.accountDataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(ACCOUNTS_KEY))
        }
    }

    private fun getCurrentAccounts(preferences: Preferences): List<SavedAccount> {
        val accountsJson = preferences[stringPreferencesKey(ACCOUNTS_KEY)]
        return if (accountsJson != null) {
            try {
                json.decodeFromString<List<SavedAccount>>(accountsJson)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}
