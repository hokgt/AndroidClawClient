package com.clawtalk.android.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.local.MessageDao
import com.clawtalk.android.data.local.entity.MessageEntity
import com.clawtalk.android.data.remote.OpenAiApiClient
import com.clawtalk.android.data.repository.SettingsRepository
import com.clawtalk.android.domain.model.Message
import com.clawtalk.android.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentAgentId: String = ""
    private var gatewayUrl: String = ""
    private var authToken: String = ""
    private val userId = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            settingsRepository.gatewayUrl.collect { url ->
                gatewayUrl = url
            }
        }
        viewModelScope.launch {
            settingsRepository.authToken.collect { token ->
                authToken = token
            }
        }
    }

    fun setAgentId(agentId: String) {
        currentAgentId = agentId
        loadMessages()
    }

    private fun loadMessages() {
        if (currentAgentId.isBlank()) return

        viewModelScope.launch {
            messageDao.getMessagesBySession(currentAgentId)
                .map { entities ->
                    entities.map { it.toMessage() }
                }
                .collect { messageList ->
                    _messages.value = messageList
                }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendMessage() {
        val content = _uiState.value.inputText.trim()
        if (content.isBlank() || currentAgentId.isBlank()) return

        viewModelScope.launch {
            // Save user message
            val userMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = currentAgentId,
                content = content,
                role = MessageRole.USER.name,
                timestamp = System.currentTimeMillis(),
                isVoice = false
            )
            messageDao.insertMessage(userMessage)
            _uiState.value = _uiState.value.copy(inputText = "", isLoading = true, error = null)

            // Build message history for API
            val history = _messages.value.map { msg ->
                OpenAiApiClient.ChatMessage(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        else -> "system"
                    },
                    content = msg.content
                )
            } + OpenAiApiClient.ChatMessage(role = "user", content = content)

            // Collect AI response
            var assistantContent = ""

            openAiApiClient.sendMessageStream(
                gatewayUrl = gatewayUrl,
                authToken = authToken,
                agentId = currentAgentId,
                messages = history,
                userId = userId
            ).collect { event ->
                when (event) {
                    is OpenAiApiClient.StreamEvent.Content -> {
                        assistantContent += event.text
                        // Update UI with streaming content (optional - could show typing indicator)
                    }
                    is OpenAiApiClient.StreamEvent.Done -> {
                        // Save complete message
                        val assistantMessage = MessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = currentAgentId,
                            content = assistantContent,
                            role = MessageRole.ASSISTANT.name,
                            timestamp = System.currentTimeMillis(),
                            isVoice = false
                        )
                        messageDao.insertMessage(assistantMessage)
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                    is OpenAiApiClient.StreamEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = event.message
                        )
                    }
                }
            }
        }
    }

    private fun MessageEntity.toMessage(): Message {
        return Message(
            id = id,
            sessionId = sessionId,
            content = content,
            role = MessageRole.valueOf(role),
            timestamp = timestamp,
            isVoice = isVoice,
            audioUrl = audioUrl
        )
    }

    data class ChatUiState(
        val inputText: String = "",
        val isLoading: Boolean = false,
        val error: String? = null
    )
}
