package com.clawtalk.android.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRecorder @Inject constructor(
    private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTime: Long = 0

    fun startRecording(): File? {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        currentFile = file

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startTime = System.currentTimeMillis()
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            return null
        }
    }

    fun stopRecording(): RecordingResult? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            currentFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    RecordingResult(file = file, durationSeconds = duration)
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            null
        }
    }

    fun cancelRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        recorder = null
        currentFile?.delete()
        currentFile = null
    }

    private fun cleanup() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        currentFile?.delete()
        currentFile = null
    }

    data class RecordingResult(
        val file: File,
        val durationSeconds: Int
    )
}
