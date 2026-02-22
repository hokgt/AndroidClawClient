package com.clawtalk.android.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ChatApiMessage(val role: String, val content: String)

@Singleton
class OpenAIApiService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val streamingClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    fun streamChat(
        gatewayUrl: String,
        authToken: String,
        agentId: String,
        userId: String,
        messages: List<ChatApiMessage>
    ): Flow<String> = flow {
        val messagesArray = JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        val body = JSONObject().apply {
            put("model", "openclaw:$agentId")
            put("messages", messagesArray)
            put("stream", true)
            put("user", userId)
        }.toString()

        val request = Request.Builder()
            .url("${gatewayUrl.trimEnd('/')}/v1/chat/completions")
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-openclaw-agent-id", agentId)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = streamingClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw Exception("HTTP ${response.code}: ${response.message}. $errorBody".trim())
        }

        val reader = response.body?.byteStream()?.bufferedReader()
            ?: run { response.close(); throw Exception("Empty response body") }

        reader.use { br ->
            while (true) {
                val line = br.readLine() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices")
                        val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
                        val content = delta?.optString("content", "")
                        if (!content.isNullOrEmpty()) {
                            emit(content)
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE lines
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun testConnection(gatewayUrl: String, authToken: String): Result<String> {
        return try {
            val request = Request.Builder()
                .url("${gatewayUrl.trimEnd('/')}/v1/models")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val code = response.code
            response.close()
            if (response.isSuccessful) {
                Result.success("Connected (HTTP $code)")
            } else {
                Result.failure(Exception("HTTP $code: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
