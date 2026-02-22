package com.clawtalk.android.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.local.MessageDao
import com.clawtalk.android.data.local.entity.MessageEntity
import com.clawtalk.android.data.remote.OpenAiApiClient
import com.clawtalk.android.data.remote.VoiceChatClient
import com.clawtalk.android.data.repository.SettingsRepository
import com.clawtalk.android.domain.model.Message
import com.clawtalk.android.domain.model.MessageRole
import com.clawtalk.android.voice.AudioRecorder
import com.clawtalk.android.voice.VoiceMessagePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient,
    private val voiceChatClient: VoiceChatClient,
    private val audioRecorder: AudioRecorder,
    val voicePlayer: VoiceMessagePlayer
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
            settingsRepository.gatewayUrl.collect { url -> gatewayUrl = url }
        }
        viewModelScope.launch {
            settingsRepository.authToken.collect { token -> authToken = token }
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
                .map { entities -> entities.map { it.toMessage() } }
                .collect { _messages.value = it }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun sendMessage() {
        val content = _uiState.value.inputText.trim()
        if (content.isBlank() || currentAgentId.isBlank()) return
        doSendTextMessage(content)
    }

    fun startVoiceRecording() {
        val file = audioRecorder.startRecording()
        if (file != null) {
            _uiState.value = _uiState.value.copy(isRecording = true)
        }
    }

    fun stopVoiceRecording(cancelled: Boolean) {
        if (cancelled) {
            audioRecorder.cancelRecording()
            _uiState.value = _uiState.value.copy(isRecording = false)
            return
        }

        val result = audioRecorder.stopRecording()
        _uiState.value = _uiState.value.copy(isRecording = false)

        if (result != null && result.durationSeconds > 0) {
            // Upload to server for STT
            transcribeAndSendVoice(result.file, result.durationSeconds)
        }
    }

    private fun transcribeAndSendVoice(audioFile: File, duration: Int) {
        if (currentAgentId.isBlank() || gatewayUrl.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Single call: upload audio → relay saves OGG to OpenClaw media/inbound,
            // transcribes via Google STT, sends to agent with media tag (like Telegram)
            val result = withContext(Dispatchers.IO) {
                voiceChatClient.sendVoiceMessage(
                    gatewayUrl = gatewayUrl,
                    authToken = authToken,
                    agentId = currentAgentId,
                    audioFile = audioFile,
                    userId = userId
                )
            }

            result.onSuccess { vcResponse ->
                // Save user voice message
                val displayText = if (vcResponse.transcript.isNotBlank()) {
                    "🎤 ${vcResponse.transcript}"
                } else {
                    "🎤 Voice message (${duration}s)"
                }

                val voiceMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = currentAgentId,
                    content = displayText,
                    role = MessageRole.USER.name,
                    timestamp = System.currentTimeMillis(),
                    isVoice = true,
                    audioUrl = audioFile.absolutePath,
                    audioDuration = duration
                )
                messageDao.insertMessage(voiceMessage)

                // Save agent reply
                if (vcResponse.agentReply.isNotBlank()) {
                    val assistantMessage = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = currentAgentId,
                        content = vcResponse.agentReply,
                        role = MessageRole.ASSISTANT.name,
                        timestamp = System.currentTimeMillis(),
                        isVoice = false
                    )
                    messageDao.insertMessage(assistantMessage)
                }

                _uiState.value = _uiState.value.copy(isLoading = false)
            }.onFailure { err ->
                // Save voice message even on error
                val voiceMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = currentAgentId,
                    content = "🎤 Voice message (${duration}s)",
                    role = MessageRole.USER.name,
                    timestamp = System.currentTimeMillis(),
                    isVoice = true,
                    audioUrl = audioFile.absolutePath,
                    audioDuration = duration
                )
                messageDao.insertMessage(voiceMessage)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = err.message ?: "Voice chat failed"
                )
            }
        }
    }

    private fun doSendTextMessage(content: String) {
        if (currentAgentId.isBlank()) return

        viewModelScope.launch {
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

            val history = buildMessageHistory() + OpenAiApiClient.ChatMessage(role = "user", content = content)

            val result = withContext(Dispatchers.IO) {
                openAiApiClient.sendMessage(
                    gatewayUrl = gatewayUrl,
                    authToken = authToken,
                    agentId = currentAgentId,
                    messages = history,
                    userId = userId
                )
            }

            handleAiResponse(result)
        }
    }

    private fun buildMessageHistory(): List<OpenAiApiClient.ChatMessage> {
        return _messages.value.map { msg ->
            val text = if (msg.isVoice && msg.content.startsWith("🎤 ")) {
                msg.content.removePrefix("🎤 ")
            } else {
                msg.content
            }
            OpenAiApiClient.ChatMessage(
                role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> "system"
                },
                content = text
            )
        }
    }

    private suspend fun handleAiResponse(result: Result<String>) {
        result.onSuccess { responseContent ->
            val assistantMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = currentAgentId,
                content = responseContent,
                role = MessageRole.ASSISTANT.name,
                timestamp = System.currentTimeMillis(),
                isVoice = false
            )
            messageDao.insertMessage(assistantMessage)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = err.message ?: "Failed to get response"
            )
        }
    }

    fun playVoiceMessage(messageId: String, audioPath: String) {
        voicePlayer.play(messageId, audioPath)
    }

    fun stopVoicePlayback() {
        voicePlayer.stop()
    }

    private fun MessageEntity.toMessage(): Message {
        return Message(
            id = id, sessionId = sessionId, content = content,
            role = MessageRole.valueOf(role), timestamp = timestamp,
            isVoice = isVoice, audioUrl = audioUrl, audioDuration = audioDuration
        )
    }

    data class ChatUiState(
        val inputText: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val isRecording: Boolean = false
    )
}
