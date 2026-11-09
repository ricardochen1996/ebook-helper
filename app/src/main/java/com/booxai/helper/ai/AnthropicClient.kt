package com.booxai.helper.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import android.util.Log
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        var call: Call? = null
        var emittedError = false
        try {
            val url = endpoint(baseUrl)
            if (url.isEmpty()) {
                emit(LlmEvent.Error("Base URL 未配置"))
                emittedError = true
                return@flow
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                emit(LlmEvent.Error("Base URL 需要以 http:// 或 https:// 开头"))
                emittedError = true
                return@flow
            }
            val body = JSONObject().apply {
                put("model", request.model)
                put("max_tokens", request.maxTokens)
                put("stream", true)
                put("temperature", request.temperature)
                put("system", request.system)
                put(
                    "messages",
                    JSONArray().apply {
                        request.messages.forEach { message ->
                            val image = message.image
                            val content: Any = if (image != null) {
                                JSONArray().apply {
                                    put(
                                        JSONObject()
                                            .put("type", "image")
                                            .put(
                                                "source",
                                                JSONObject()
                                                    .put("type", "base64")
                                                    .put("media_type", image.mediaType)
                                                    .put("data", image.base64)
                                            )
                                    )
                                    put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", message.content)
                                    )
                                }
                            } else {
                                message.content
                            }
                            put(JSONObject().put("role", message.role).put("content", content))
                        }
                    }
                )
            }
            val httpRequest = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(Http.JSON))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .build()

            call = Http.client.newCall(httpRequest)
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                emit(LlmEvent.Error(LlmErrors.http(response.code, errorBody)))
                emittedError = true
                return@flow
            }
            val source = response.body?.source()
            if (source == null) {
                emit(LlmEvent.Error("服务器返回了空响应"))
                emittedError = true
                return@flow
            }
            for (sse in SseReader(source).readEvents()) {
                val data = sse.data.trim()
                if (data.isEmpty()) continue
                val json = try {
                    JSONObject(data)
                } catch (_: Exception) {
                    continue
                }
                when (json.optString("type")) {
                    "content_block_delta" -> {
                        val text = json.optJSONObject("delta")?.optString("text", "")
                        if (!text.isNullOrEmpty()) emit(LlmEvent.Text(text))
                    }
                    "message_stop" -> break
                    "error" -> {
                        val message = json.optJSONObject("error")?.optString("message")
                            ?: data.take(300)
                        emit(LlmEvent.Error(message))
                        emittedError = true
                        break
                    }
                    "ping", "message_start", "content_block_start", "content_block_stop", "message_delta" -> Unit
                }
            }
            if (!emittedError) emit(LlmEvent.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "request failed: $baseUrl", e)
            if (!emittedError) emit(LlmEvent.Error(LlmErrors.network(e), e))
        } finally {
            call?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        return when {
            b.endsWith("/messages") -> b
            b.endsWith("/v1") -> "$b/messages"
            else -> "$b/v1/messages"
        }
    }

    companion object {
        private const val TAG = "ReadAskAi"
    }
}
