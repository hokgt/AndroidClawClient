package com.clawtalk.android.presentation.agentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.local.MessageDao
import com.clawtalk.android.data.remote.OpenAiApiClient
import com.clawtalk.android.data.repository.SettingsRepository
import com.clawtalk.android.domain.model.Agent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val messageDao: MessageDao,
    private val openAiApiClient: OpenAiApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val gatewayUrl = settingsRepository.gatewayUrl.first()
            val authToken = settingsRepository.authToken.first()

            if (gatewayUrl.isBlank() || authToken.isBlank()) {
                _uiState.value = AgentListUiState(agents = emptyList(), isLoading = false)
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                openAiApiClient.fetchAgents(gatewayUrl, authToken)
            }

            result.onSuccess { agentInfos ->
                val agents = agentInfos.map { info ->
                    val lastMessage = messageDao.getLastMessageForAgent(info.id)
                    Agent(
                        id = info.id,
                        name = info.name,
                        isOnline = info.status == "available",
                        lastMessage = lastMessage?.content,
                        lastMessageTime = lastMessage?.timestamp
                    )
                }
                _uiState.value = AgentListUiState(agents = agents, isLoading = false)
            }.onFailure { err ->
                _uiState.value = AgentListUiState(
                    agents = emptyList(),
                    isLoading = false,
                    error = err.message
                )
            }
        }
    }

    fun refresh() {
        loadAgents()
    }

    data class AgentListUiState(
        val agents: List<Agent> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null
    )
}
