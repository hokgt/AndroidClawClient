package com.clawtalk.android.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiApiClient @Inject constructor(
    private val gson: Gson
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun sendMessageStream(
        gatewayUrl: String,
        authToken: String,
        agentId: String,
        messages: List<ChatMessage>,
        userId: String
    ): Flow<StreamEvent> = flow {
        val url = gatewayUrl.trimEnd('/')
        val requestBody = ChatCompletionRequest(
            model = "openclaw:$agentId",
            messages = messages,
            stream = true,
            user = userId
        )

        val request = Request.Builder()
            .url("$url/v1/chat/completions")
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .header("x-openclaw-agent-id", agentId)
            .build()

        val call = client.newCall(request)
        
        try {
            val response = call.execute()

            if (!response.isSuccessful) {
                emit(StreamEvent.Error("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            response.body?.byteStream()?.use { stream ->
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line == "data: [DONE]" -> {
                                emit(StreamEvent.Done)
                            }
                            line.startsWith("data: ") -> {
                                val json = line.substring(6)
                                try {
                                    val chunk = gson.fromJson(json, ChatCompletionChunk::class.java)
                                    val content = chunk.choices.firstOrNull()?.delta?.content
                                    if (!content.isNullOrBlank()) {
                                        emit(StreamEvent.Content(content))
                                    }
                                } catch (e: Exception) {
                                    // Skip malformed chunks
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun testConnection(
        gatewayUrl: String,
        authToken: String
    ): Result<String> {
        return try {
            val url = gatewayUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$url/v1/pair")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $authToken")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success("Connected successfully!")
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class AgentInfo(
        val id: String,
        val name: String,
        val status: String
    )

    data class AgentsResponse(
        val agents: List<AgentInfo>
    )

    suspend fun fetchAgents(
        gatewayUrl: String,
        authToken: String
    ): Result<List<AgentInfo>> {
        return try {
            val url = gatewayUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$url/v1/agents")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val agentsResponse = gson.fromJson(body, AgentsResponse::class.java)
                Result.success(agentsResponse.agents)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        gatewayUrl: String,
        authToken: String,
        agentId: String,
        messages: List<ChatMessage>,
        userId: String
    ): Result<String> {
        return try {
            val url = gatewayUrl.trimEnd('/')
            val requestBody = ChatCompletionRequest(
                model = "openclaw:$agentId",
                messages = messages,
                stream = false,
                user = userId
            )

            val request = Request.Builder()
                .url("$url/v1/chat/completions")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $authToken")
                .header("Content-Type", "application/json")
                .header("x-openclaw-agent-id", agentId)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: "{}"
            val chatResponse = gson.fromJson(body, ChatCompletionResponse::class.java)
            val content = chatResponse.choices.firstOrNull()?.message?.content ?: ""
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class ChatCompletionResponse(
        val id: String,
        val choices: List<ResponseChoice>
    )

    data class ResponseChoice(
        val index: Int,
        val message: ResponseMessage,
        val finish_reason: String?
    )

    data class ResponseMessage(
        val role: String,
        val content: String
    )

    data class ChatMessage(
        val role: String,
        val content: String
    )

    data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean,
        val user: String
    )

    data class ChatCompletionChunk(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<Choice>
    )

    data class Choice(
        val index: Int,
        val delta: Delta,
        val finish_reason: String?
    )

    data class Delta(
        val role: String?,
        val content: String?
    )

    sealed class StreamEvent {
        data class Content(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }
}
