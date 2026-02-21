package com.clawtalk.android.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private var webSocket: WebSocket? = null
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableStateFlow<ServerMessage?>(null)
    val messages: StateFlow<ServerMessage?> = _messages

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    fun connect(url: String, apiKey: String) {
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected
                // Send auth message
                val authMsg = ClientMessage.Auth(apiKey = apiKey)
                sendMessage(authMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = parseServerMessage(text)
                    _messages.value = message
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Handle binary audio data if needed
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
            }
        })
    }

    fun sendMessage(message: ClientMessage) {
        val json = gson.toJson(message)
        webSocket?.send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "Closing connection")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun parseServerMessage(json: String): ServerMessage {
        val map = gson.fromJson(json, Map::class.java)
        return when (map["type"]) {
            "auth_success" -> gson.fromJson(json, ServerMessage.AuthSuccess::class.java)
            "text_reply" -> gson.fromJson(json, ServerMessage.TextReply::class.java)
            "audio_reply" -> gson.fromJson(json, ServerMessage.AudioReply::class.java)
            "transcription" -> gson.fromJson(json, ServerMessage.Transcription::class.java)
            "error" -> gson.fromJson(json, ServerMessage.Error::class.java)
            "pong" -> gson.fromJson(json, ServerMessage.Pong::class.java)
            else -> throw IllegalArgumentException("Unknown message type: ${map["type"]}")
        }
    }
}
