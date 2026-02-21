package com.clawtalk.android.voice

import com.clawtalk.android.data.repository.VoiceRepositoryImpl
import com.clawtalk.android.domain.model.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceEngine @Inject constructor(
    private val audioCapture: AudioCapture,
    private val audioPlayer: AudioPlayer,
    private val voiceRepository: VoiceRepositoryImpl
) {
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState

    private val scope = CoroutineScope(Dispatchers.IO)

    fun startListening(sessionId: String) {
        if (_voiceState.value != VoiceState.Idle) return

        _voiceState.value = VoiceState.Listening

        scope.launch {
            try {
                audioCapture.startCapture().collect { audioData ->
                    voiceRepository.sendAudioChunk(audioData, sessionId)
                }
            } catch (e: Exception) {
                _voiceState.value = VoiceState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun stopListening(sessionId: String) {
        audioCapture.stopCapture()
        voiceRepository.endAudio(sessionId)
        _voiceState.value = VoiceState.Processing
    }

    fun startSpeaking() {
        _voiceState.value = VoiceState.Speaking
        audioPlayer.startPlayback()
    }

    fun playAudioChunk(audioData: ByteArray) {
        audioPlayer.playAudioChunk(audioData)
    }

    fun stopSpeaking() {
        audioPlayer.stopPlayback()
        _voiceState.value = VoiceState.Idle
    }
}
