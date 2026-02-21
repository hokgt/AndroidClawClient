package com.clawtalk.android.data.repository

import com.clawtalk.android.data.local.MessageDao
import com.clawtalk.android.data.local.entity.MessageEntity
import com.clawtalk.android.data.remote.ClientMessage
import com.clawtalk.android.data.remote.WebSocketClient
import com.clawtalk.android.domain.model.Message
import com.clawtalk.android.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val webSocketClient: WebSocketClient
) {
    fun getMessages(sessionId: String): Flow<List<Message>> {
        return messageDao.getMessagesBySession(sessionId).map { entities ->
            entities.map { it.toMessage() }
        }
    }

    suspend fun sendMessage(content: String, sessionId: String) {
        // Save to local DB
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = content,
            role = MessageRole.USER.name,
            timestamp = System.currentTimeMillis(),
            isVoice = false
        )
        messageDao.insertMessage(message)

        // Send via WebSocket
        webSocketClient.sendMessage(
            ClientMessage.TextMessage(
                content = content,
                sessionId = sessionId
            )
        )
    }

    suspend fun saveAssistantMessage(content: String, sessionId: String) {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = content,
            role = MessageRole.ASSISTANT.name,
            timestamp = System.currentTimeMillis(),
            isVoice = false
        )
        messageDao.insertMessage(message)
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
}
