package com.clawtalk.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val content: String,
    val role: String,
    val timestamp: Long,
    val isVoice: Boolean = false,
    val audioUrl: String? = null,
    val audioDuration: Int = 0
)
