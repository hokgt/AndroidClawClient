package com.clawtalk.android.data.repository

import com.clawtalk.android.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {
    val gatewayUrl: Flow<String> = settingsDataStore.gatewayUrl
    val authToken: Flow<String> = settingsDataStore.authToken
    val agentIds: Flow<String> = settingsDataStore.agentIds
    val isConfigured: Flow<Boolean> = settingsDataStore.isConfigured

    suspend fun saveSettings(gatewayUrl: String, authToken: String, agentIds: String) {
        settingsDataStore.saveSettings(gatewayUrl, authToken, agentIds)
    }

    suspend fun clearSettings() {
        settingsDataStore.clearSettings()
    }
}
