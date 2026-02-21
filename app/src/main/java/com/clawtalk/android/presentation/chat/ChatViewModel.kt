package com.clawtalk.android.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.data.remote.WebSocketClient
import com.clawtalk.android.data.repository.ChatRepositoryImpl
import com.clawtalk.android.domain.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepositoryImpl,
    private val webSocketClient: WebSocketClient
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _connectionState = MutableStateFlow<WebSocketClient.ConnectionState>(
        WebSocketClient.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<WebSocketClient.ConnectionState> = _connectionState

    private val currentSessionId = UUID.randomUUID().toString()

    init {
        observeConnectionState()
        observeMessages()
        connectToServer()
    }

    private fun connectToServer() {
        viewModelScope.launch {
            webSocketClient.connect(
                url = "ws://10.0.2.2:8080/ws", // Default for Android emulator
                apiKey = "test_api_key"
            )
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            webSocketClient.connectionState.collect { state ->
                _connectionState.value = state
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            chatRepository.getMessages(currentSessionId).collect { messages ->
                _messages.value = messages
            }
        }
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            chatRepository.sendMessage(content, currentSessionId)
        }
    }
}
