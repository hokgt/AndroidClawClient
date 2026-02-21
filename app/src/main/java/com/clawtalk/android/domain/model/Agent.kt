package com.clawtalk.android.domain.model

data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val isActive: Boolean = false
)
