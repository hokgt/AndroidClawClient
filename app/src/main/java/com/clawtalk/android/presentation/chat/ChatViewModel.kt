package com.clawtalk.android.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.local.MessageDao
import com.clawtalk.android.data.local.entity.MessageEntity
import com.clawtalk.android.data.remote.OpenAiApiClient
import com.clawtalk.android.data.remote.SttClient
import com.clawtalk.android.data.remote.VoiceChatClient
import com.clawtalk.android.data.repository.SettingsRepository
import com.clawtalk.android.domain.model.Message
import com.clawtalk.android.domain.model.MessageRole
import com.clawtalk.android.voice.AudioRecorder
import com.clawtalk.android.voice.TtsEngine
import com.clawtalk.android.voice.VoiceMessagePlayer
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val openAiApiClient: OpenAiApiClient,
    private val voiceChatClient: VoiceChatClient,
    private val sttClient: SttClient,
    private val audioRecorder: AudioRecorder,
    val voicePlayer: VoiceMessagePlayer,
    private val ttsEngine: TtsEngine
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentAgentId: String = ""
    private var gatewayUrl: String = ""
    private var authToken: String = ""
    private val userId = UUID.randomUUID().toString()

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
    }

    init {
        ttsEngine.init()
    }

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

        val voiceMessageId = UUID.randomUUID().toString()

        viewModelScope.launch {
            // 1. Save voice bubble IMMEDIATELY so user sees it right away
            val voiceMessage = MessageEntity(
                id = voiceMessageId,
                sessionId = currentAgentId,
                content = "🎤 Voice message (${duration}s)",
                role = MessageRole.USER.name,
                timestamp = System.currentTimeMillis(),
                isVoice = true,
                audioUrl = audioFile.absolutePath,
                audioDuration = duration
            )
            messageDao.insertMessage(voiceMessage)

            // 2. Show loading indicator
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // 3. STT: transcribe audio to text
                val sttResult = withContext(Dispatchers.IO) {
                    sttClient.transcribeAudio(
                        gatewayUrl = gatewayUrl,
                        authToken = authToken,
                        audioFile = audioFile
                    )
                }

                val transcript = sttResult.getOrNull()

                if (transcript.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Could not transcribe voice message"
                    )
                    return@launch
                }

                // 4. Update voice bubble with transcription
                val updatedVoice = voiceMessage.copy(content = "🎤 $transcript")
                messageDao.insertMessage(updatedVoice)

                // 5. Send transcribed text via the SAME chat completions API as text messages
                val history = buildMessageHistory() + OpenAiApiClient.ChatMessage(role = "user", content = transcript)

                val chatResult = withContext(Dispatchers.IO) {
                    openAiApiClient.sendMessage(
                        gatewayUrl = gatewayUrl,
                        authToken = authToken,
                        agentId = currentAgentId,
                        messages = history,
                        userId = userId
                    )
                }

                chatResult.onSuccess { replyText ->
                    // 6. TTS: synthesize agent reply as voice
                    val replyId = UUID.randomUUID().toString()
                    val ttsFile = File(context.cacheDir, "tts_${replyId}.wav")

                    val ttsSuccess = withContext(Dispatchers.IO) {
                        ttsEngine.synthesize(replyText, ttsFile)
                    }

                    val assistantMessage = if (ttsSuccess && ttsFile.exists()) {
                        val estimatedDuration = (ttsFile.length() / 32000).toInt().coerceAtLeast(1)
                        MessageEntity(
                            id = replyId,
                            sessionId = currentAgentId,
                            content = replyText,
                            role = MessageRole.ASSISTANT.name,
                            timestamp = System.currentTimeMillis(),
                            isVoice = true,
                            audioUrl = ttsFile.absolutePath,
                            audioDuration = estimatedDuration
                        )
                    } else {
                        // Fallback to text if TTS fails
                        MessageEntity(
                            id = replyId,
                            sessionId = currentAgentId,
                            content = replyText,
                            role = MessageRole.ASSISTANT.name,
                            timestamp = System.currentTimeMillis(),
                            isVoice = false
                        )
                    }
                    messageDao.insertMessage(assistantMessage)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }.onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = err.message ?: "Failed to get agent reply"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Voice chat failed"
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
