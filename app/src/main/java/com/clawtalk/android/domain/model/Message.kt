package com.clawtalk.android.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val content: String,
    val role: MessageRole,
    val timestamp: Long,
    val isVoice: Boolean = false,
    val audioUrl: String? = null
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}
