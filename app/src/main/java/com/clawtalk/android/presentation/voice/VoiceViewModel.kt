package com.clawtalk.android.presentation.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawtalk.android.domain.model.VoiceState
import com.clawtalk.android.voice.VoiceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val voiceEngine: VoiceEngine
) : ViewModel() {

    val voiceState: StateFlow<VoiceState> = voiceEngine.voiceState
    private val sessionId = UUID.randomUUID().toString()

    fun startListening() {
        viewModelScope.launch {
            voiceEngine.startListening(sessionId)
        }
    }

    fun stopListening() {
        viewModelScope.launch {
            voiceEngine.stopListening(sessionId)
        }
    }
}
