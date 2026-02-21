package com.clawtalk.android.data.remote

import com.google.gson.annotations.SerializedName

// Client -> Server Messages
sealed class ClientMessage {
    data class Auth(
        @SerializedName("type") val type: String = "auth",
        @SerializedName("api_key") val apiKey: String
    ) : ClientMessage()

    data class TextMessage(
        @SerializedName("type") val type: String = "text_message",
        @SerializedName("content") val content: String,
        @SerializedName("session_id") val sessionId: String? = null
    ) : ClientMessage()

    data class AudioChunk(
        @SerializedName("type") val type: String = "audio_chunk",
        @SerializedName("data") val data: String, // base64 encoded
        @SerializedName("format") val format: String = "pcm_16khz_mono",
        @SerializedName("session_id") val sessionId: String? = null
    ) : ClientMessage()

    data class EndAudio(
        @SerializedName("type") val type: String = "end_audio",
        @SerializedName("session_id") val sessionId: String? = null
    ) : ClientMessage()

    data class Ping(
        @SerializedName("type") val type: String = "ping"
    ) : ClientMessage()
}

// Server -> Client Messages
sealed class ServerMessage {
    data class AuthSuccess(
        val type: String,
        @SerializedName("user_id") val userId: String,
        @SerializedName("session_id") val sessionId: String
    ) : ServerMessage()

    data class TextReply(
        val type: String,
        val content: String,
        @SerializedName("message_id") val messageId: String,
        @SerializedName("session_id") val sessionId: String
    ) : ServerMessage()

    data class AudioReply(
        val type: String,
        val data: String, // base64 encoded
        val format: String,
        @SerializedName("session_id") val sessionId: String
    ) : ServerMessage()

    data class Transcription(
        val type: String,
        val text: String,
        @SerializedName("is_final") val isFinal: Boolean,
        @SerializedName("session_id") val sessionId: String
    ) : ServerMessage()

    data class Error(
        val type: String,
        val message: String,
        val code: String? = null
    ) : ServerMessage()

    data class Pong(
        val type: String
    ) : ServerMessage()
}
