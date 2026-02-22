package com.clawtalk.android.data.remote

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceChatClient @Inject constructor(
    private val gson: Gson
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class VoiceChatResponse(
        val transcript: String,
        val agentReply: String,
        val voiceFile: String?,
        val provider: String?
    )

    suspend fun sendVoiceMessage(
        gatewayUrl: String,
        authToken: String,
        agentId: String,
        audioFile: File,
        userId: String,
        language: String = "id-ID"
    ): Result<VoiceChatResponse> {
        return try {
            val url = gatewayUrl.trimEnd('/')

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaType())
                )
                .addFormDataPart("agentId", agentId)
                .addFormDataPart("language", language)
                .addFormDataPart("user", userId)
                .build()

            val request = Request.Builder()
                .url("$url/v1/voice-chat")
                .post(requestBody)
                .header("Authorization", "Bearer $authToken")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return Result.failure(Exception("Voice chat failed: HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: "{}"
            val vcResponse = gson.fromJson(body, VoiceChatResponse::class.java)
            Result.success(vcResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
