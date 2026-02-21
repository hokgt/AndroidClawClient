package com.clawtalk.android.domain.model

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    object Processing : VoiceState()
    object Speaking : VoiceState()
    data class Error(val message: String) : VoiceState()
}
