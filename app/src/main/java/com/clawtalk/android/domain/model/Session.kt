package com.clawtalk.android.domain.model

data class Session(
    val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)
