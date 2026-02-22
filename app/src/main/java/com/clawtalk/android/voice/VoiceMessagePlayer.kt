package com.clawtalk.android.voice

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceMessagePlayer @Inject constructor() {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingId: String? = null

    private val _playingState = MutableStateFlow<PlayingState>(PlayingState.Idle)
    val playingState: StateFlow<PlayingState> = _playingState

    fun play(messageId: String, filePath: String) {
        // Stop current playback if any
        stop()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
            }
            currentPlayingId = messageId
            _playingState.value = PlayingState.Playing(messageId)

            mediaPlayer?.setOnCompletionListener {
                _playingState.value = PlayingState.Idle
                currentPlayingId = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _playingState.value = PlayingState.Idle
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        currentPlayingId = null
        _playingState.value = PlayingState.Idle
    }

    fun isPlaying(messageId: String): Boolean {
        return currentPlayingId == messageId && mediaPlayer?.isPlaying == true
    }

    sealed class PlayingState {
        object Idle : PlayingState()
        data class Playing(val messageId: String) : PlayingState()
    }
}
