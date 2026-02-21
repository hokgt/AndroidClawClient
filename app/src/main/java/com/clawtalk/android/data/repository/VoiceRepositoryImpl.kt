package com.clawtalk.android.data.repository

import com.clawtalk.android.data.remote.ClientMessage
import com.clawtalk.android.data.remote.WebSocketClient
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepositoryImpl @Inject constructor(
    private val webSocketClient: WebSocketClient
) {
    fun sendAudioChunk(audioData: ByteArray, sessionId: String) {
        val base64Data = Base64.encodeToString(audioData, Base64.NO_WRAP)
        webSocketClient.sendMessage(
            ClientMessage.AudioChunk(
                data = base64Data,
                sessionId = sessionId
            )
        )
    }

    fun endAudio(sessionId: String) {
        webSocketClient.sendMessage(
            ClientMessage.EndAudio(sessionId = sessionId)
        )
    }
}
