package com.clawtalk.android.presentation.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

    // ── Reply/Quote ──────────────────────────────────────────────

    fun setReplyTo(message: Message) {
        _uiState.value = _uiState.value.copy(replyTo = message)
    }

    fun clearReply() {
        _uiState.value = _uiState.value.copy(replyTo = null)
    }

    // ── Forward ──────────────────────────────────────────────────

    fun forwardMessage(message: Message, targetAgentId: String) {
        viewModelScope.launch {
            val forwardedContent = "[Forwarded]\n${message.content}"
            val forwardEntity = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = targetAgentId,
                content = forwardedContent,
                role = MessageRole.USER.name,
                timestamp = System.currentTimeMillis(),
                isVoice = false,
                mediaUrl = message.mediaUrl,
                mediaType = message.mediaType,
                mediaName = message.mediaName,
                mediaSize = message.mediaSize
            )
            messageDao.insertMessage(forwardEntity)

            // Send to agent
            val history = listOf(OpenAiApiClient.ChatMessage(role = "user", content = forwardedContent))
            val result = withContext(Dispatchers.IO) {
                openAiApiClient.sendMessage(
                    gatewayUrl = gatewayUrl,
                    authToken = authToken,
                    agentId = targetAgentId,
                    messages = history,
                    userId = userId
                )
            }
            result.onSuccess { replyText ->
                val reply = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = targetAgentId,
                    content = replyText,
                    role = MessageRole.ASSISTANT.name,
                    timestamp = System.currentTimeMillis(),
                    isVoice = false
                )
                messageDao.insertMessage(reply)
            }
        }
    }

    // ── Send Media (picture/file) ────────────────────────────────

    fun sendMedia(uri: Uri) {
        if (currentAgentId.isBlank()) return

        viewModelScope.launch {
            try {
                val (fileName, fileSize, mimeType) = getFileInfo(uri)
                val isImage = mimeType?.startsWith("image/") == true
                val localPath = copyToLocal(uri, fileName)

                val mediaMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = currentAgentId,
                    content = if (isImage) "📷 Photo" else "📎 $fileName",
                    role = MessageRole.USER.name,
                    timestamp = System.currentTimeMillis(),
                    isVoice = false,
                    mediaUrl = localPath,
                    mediaType = if (isImage) "image" else "file",
                    mediaName = fileName,
                    mediaSize = fileSize
                )
                messageDao.insertMessage(mediaMsg)

                // Send description to agent
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val description = if (isImage) {
                    "[User sent an image: $fileName]"
                } else {
                    "[User sent a file: $fileName (${formatFileSize(fileSize)})]"
                }

                // Include caption if there's text in input
                val caption = _uiState.value.inputText.trim()
                val fullContent = if (caption.isNotBlank()) "$description\nCaption: $caption" else description
                if (caption.isNotBlank()) {
                    _uiState.value = _uiState.value.copy(inputText = "")
                }

                val replyTo = _uiState.value.replyTo
                clearReply()

                val history = buildMessageHistory() + OpenAiApiClient.ChatMessage(role = "user", content = fullContent)
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to send: ${e.message}")
            }
        }
    }

    private fun getFileInfo(uri: Uri): Triple<String, Long, String?> {
        var name = "file"
        var size = 0L
        val mimeType = context.contentResolver.getType(uri)

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: "file"
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        return Triple(name, size, mimeType)
    }

    private fun copyToLocal(uri: Uri, fileName: String): String {
        val mediaDir = File(context.cacheDir, "media").also { it.mkdirs() }
        val destFile = File(mediaDir, "${UUID.randomUUID()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        return destFile.absolutePath
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    // ── Send Text ────────────────────────────────────────────────

    fun sendMessage() {
        val content = _uiState.value.inputText.trim()
        if (content.isBlank() || currentAgentId.isBlank()) return
        doSendTextMessage(content)
    }

    private fun doSendTextMessage(content: String) {
        if (currentAgentId.isBlank()) return

        val replyTo = _uiState.value.replyTo
        clearReply()

        viewModelScope.launch {
            val userMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = currentAgentId,
                content = content,
                role = MessageRole.USER.name,
                timestamp = System.currentTimeMillis(),
                isVoice = false,
                replyToId = replyTo?.id,
                replyToContent = replyTo?.content?.take(100),
                replyToRole = replyTo?.role?.name
            )
            messageDao.insertMessage(userMessage)
            _uiState.value = _uiState.value.copy(inputText = "", isLoading = true, error = null)

            // Include quoted context in the message to the agent
            val messageForAgent = if (replyTo != null) {
                "[Replying to: \"${replyTo.content.take(200)}\"]\n$content"
            } else {
                content
            }

            val history = buildMessageHistory() + OpenAiApiClient.ChatMessage(role = "user", content = messageForAgent)

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

    // ── Voice ────────────────────────────────────────────────────

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
            transcribeAndSendVoice(result.file, result.durationSeconds)
        }
    }

    private fun transcribeAndSendVoice(audioFile: File, duration: Int) {
        if (currentAgentId.isBlank() || gatewayUrl.isBlank()) return
        val voiceMessageId = UUID.randomUUID().toString()

        viewModelScope.launch {
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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val sttResult = withContext(Dispatchers.IO) {
                    sttClient.transcribeAudio(gatewayUrl = gatewayUrl, authToken = authToken, audioFile = audioFile)
                }
                val transcript = sttResult.getOrNull()
                if (transcript.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Could not transcribe voice message")
                    return@launch
                }
                messageDao.insertMessage(voiceMessage.copy(content = "🎤 $transcript"))

                val history = buildMessageHistory() + OpenAiApiClient.ChatMessage(role = "user", content = transcript)
                val chatResult = withContext(Dispatchers.IO) {
                    openAiApiClient.sendMessage(gatewayUrl = gatewayUrl, authToken = authToken, agentId = currentAgentId, messages = history, userId = userId)
                }
                chatResult.onSuccess { replyText ->
                    val replyId = UUID.randomUUID().toString()
                    val ttsFile = File(context.cacheDir, "tts_${replyId}.wav")
                    val ttsSuccess = withContext(Dispatchers.IO) { ttsEngine.synthesize(replyText, ttsFile) }
                    val assistantMessage = if (ttsSuccess && ttsFile.exists()) {
                        val dur = (ttsFile.length() / 32000).toInt().coerceAtLeast(1)
                        MessageEntity(id = replyId, sessionId = currentAgentId, content = replyText, role = MessageRole.ASSISTANT.name, timestamp = System.currentTimeMillis(), isVoice = true, audioUrl = ttsFile.absolutePath, audioDuration = dur)
                    } else {
                        MessageEntity(id = replyId, sessionId = currentAgentId, content = replyText, role = MessageRole.ASSISTANT.name, timestamp = System.currentTimeMillis(), isVoice = false)
                    }
                    messageDao.insertMessage(assistantMessage)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }.onFailure { err ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = err.message ?: "Failed to get agent reply")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Voice chat failed")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun buildMessageHistory(): List<OpenAiApiClient.ChatMessage> {
        return _messages.value.map { msg ->
            val text = if (msg.isVoice && msg.content.startsWith("🎤 ")) msg.content.removePrefix("🎤 ") else msg.content
            OpenAiApiClient.ChatMessage(
                role = when (msg.role) { MessageRole.USER -> "user"; MessageRole.ASSISTANT -> "assistant"; else -> "system" },
                content = text
            )
        }
    }

    private suspend fun handleAiResponse(result: Result<String>) {
        result.onSuccess { responseContent ->
            val assistantMessage = MessageEntity(
                id = UUID.randomUUID().toString(), sessionId = currentAgentId,
                content = responseContent, role = MessageRole.ASSISTANT.name,
                timestamp = System.currentTimeMillis(), isVoice = false
            )
            messageDao.insertMessage(assistantMessage)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(isLoading = false, error = err.message ?: "Failed to get response")
        }
    }

    fun playVoiceMessage(messageId: String, audioPath: String) { voicePlayer.play(messageId, audioPath) }
    fun stopVoicePlayback() { voicePlayer.stop() }

    private fun MessageEntity.toMessage(): Message {
        return Message(
            id = id, sessionId = sessionId, content = content,
            role = MessageRole.valueOf(role), timestamp = timestamp,
            isVoice = isVoice, audioUrl = audioUrl, audioDuration = audioDuration,
            replyToId = replyToId, replyToContent = replyToContent,
            replyToRole = replyToRole?.let { try { MessageRole.valueOf(it) } catch (_: Exception) { null } },
            mediaUrl = mediaUrl, mediaType = mediaType, mediaName = mediaName, mediaSize = mediaSize
        )
    }

    data class ChatUiState(
        val inputText: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val isRecording: Boolean = false,
        val replyTo: Message? = null
    )
}
