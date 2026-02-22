package com.clawtalk.android.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val content: String,
    val role: MessageRole,
    val timestamp: Long,
    val isVoice: Boolean = false,
    val audioUrl: String? = null,
    val audioDuration: Int = 0,
    // Reply/quote
    val replyToId: String? = null,
    val replyToContent: String? = null,
    val replyToRole: MessageRole? = null,
    // Media
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val mediaName: String? = null,
    val mediaSize: Long = 0
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}
