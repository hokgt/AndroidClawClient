package com.clawtalk.android.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TtsEngine @Inject constructor(
    private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isReady = false

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                isReady = true
            }
        }
    }

    suspend fun synthesize(text: String, outputFile: File): Boolean {
        if (!isReady) {
            // Wait a bit for init
            repeat(20) {
                if (isReady) return@repeat
                kotlinx.coroutines.delay(100)
            }
            if (!isReady) return false
        }

        return suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(true)
                }
                @Deprecated("Deprecated")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(false)
                }
            })

            val result = tts?.synthesizeToFile(text, null, outputFile, utteranceId)
            if (result != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
