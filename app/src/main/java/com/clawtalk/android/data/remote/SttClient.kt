package com.clawtalk.android.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SttClient @Inject constructor() {
    private val client = okhttp3.OkHttpClient()

    suspend fun transcribeAudio(
        gatewayUrl: String,
        authToken: String,
        audioFile: File,
        language: String = "id-ID"
    ): Result<String> {
        return try {
            val url = gatewayUrl.trimEnd('/')

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaType())
                )
                .addFormDataPart("language", language)
                .build()

            val request = Request.Builder()
                .url("$url/v1/stt")
                .post(requestBody)
                .header("Authorization", "Bearer $authToken")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return Result.failure(Exception("STT failed: HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val transcript = json.optString("transcript", "")

            if (transcript.isBlank()) {
                Result.failure(Exception("Empty transcription"))
            } else {
                Result.success(transcript)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
