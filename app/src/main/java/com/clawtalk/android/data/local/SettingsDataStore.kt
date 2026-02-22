package com.clawtalk.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    private val context: Context
) {
    private val dataStore = context.dataStore

    val gatewayUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[GATEWAY_URL] ?: ""
    }

    val authToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[AUTH_TOKEN] ?: ""
    }

    val agentIds: Flow<String> = dataStore.data.map { prefs ->
        prefs[AGENT_IDS] ?: ""
    }

    val isConfigured: Flow<Boolean> = dataStore.data.map { prefs ->
        !prefs[GATEWAY_URL].isNullOrBlank() && !prefs[AUTH_TOKEN].isNullOrBlank()
    }

    suspend fun saveSettings(gatewayUrl: String, authToken: String, agentIds: String) {
        dataStore.edit { prefs ->
            prefs[GATEWAY_URL] = gatewayUrl
            prefs[AUTH_TOKEN] = authToken
            prefs[AGENT_IDS] = agentIds
        }
    }

    suspend fun clearSettings() {
        dataStore.edit { prefs ->
            prefs.remove(GATEWAY_URL)
            prefs.remove(AUTH_TOKEN)
            prefs.remove(AGENT_IDS)
        }
    }

    companion object {
        private val GATEWAY_URL = stringPreferencesKey("gateway_url")
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val AGENT_IDS = stringPreferencesKey("agent_ids")
    }
}
