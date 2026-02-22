package com.clawtalk.android.presentation.agentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.local.MessageDao
import com.clawtalk.android.data.repository.SettingsRepository
import com.clawtalk.android.domain.model.Agent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgentListViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            settingsRepository.agentIds.collect { agentIdsString ->
                val agentIds = agentIdsString
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                if (agentIds.isEmpty()) {
                    _uiState.value = AgentListUiState(agents = emptyList(), isLoading = false)
                    return@collect
                }

                val agents = agentIds.map { agentId ->
                    val lastMessage = messageDao.getLastMessageForAgent(agentId)
                    Agent(
                        id = agentId,
                        name = agentId.replaceFirstChar { it.uppercase() },
                        isOnline = true,
                        lastMessage = lastMessage?.content,
                        lastMessageTime = lastMessage?.timestamp
                    )
                }

                _uiState.value = AgentListUiState(agents = agents, isLoading = false)
            }
        }
    }

    data class AgentListUiState(
        val agents: List<Agent> = emptyList(),
        val isLoading: Boolean = true
    )
}
