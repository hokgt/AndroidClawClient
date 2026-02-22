package com.clawtalk.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.remote.OpenAiApiClient
import com.clawtalk.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            val gatewayUrl = settingsRepository.gatewayUrl.first()
            val authToken = settingsRepository.authToken.first()
            val agentIds = settingsRepository.agentIds.first()
            _uiState.value = SettingsUiState(
                gatewayUrl = gatewayUrl,
                authToken = authToken,
                agentIds = agentIds
            )
        }
    }

    fun onGatewayUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(gatewayUrl = value)
    }

    fun onAuthTokenChange(value: String) {
        _uiState.value = _uiState.value.copy(authToken = value)
    }

    fun onAgentIdsChange(value: String) {
        _uiState.value = _uiState.value.copy(agentIds = value)
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, connectionResult = null)
            val result = openAiApiClient.testConnection(
                gatewayUrl = _uiState.value.gatewayUrl,
                authToken = _uiState.value.authToken
            )
            _uiState.value = _uiState.value.copy(isTesting = false, connectionResult = result)
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            settingsRepository.saveSettings(
                gatewayUrl = _uiState.value.gatewayUrl,
                authToken = _uiState.value.authToken,
                agentIds = _uiState.value.agentIds
            )
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }

    fun onSavedHandled() {
        _uiState.value = _uiState.value.copy(isSaved = false)
    }

    val isConfigured: kotlinx.coroutines.flow.Flow<Boolean> = settingsRepository.isConfigured

    data class SettingsUiState(
        val gatewayUrl: String = "",
        val authToken: String = "",
        val agentIds: String = "",
        val isTesting: Boolean = false,
        val connectionResult: Result<String>? = null,
        val isSaved: Boolean = false
    )
}
