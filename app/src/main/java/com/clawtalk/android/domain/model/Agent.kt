package com.clawtalk.android.domain.model

data class Agent(
    val id: String,
    val name: String,
    val isOnline: Boolean = true,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0
)
