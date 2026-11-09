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

class OpenAiClient(
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
                put("stream", true)
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", request.system))
                        request.messages.forEach { message ->
                            put(JSONObject().put("role", message.role).put("content", message.content))
                        }
                    }
                )
            }
            val httpRequest = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(Http.JSON))
                .header("Authorization", "Bearer $apiKey")
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
                if (data == "[DONE]") break
                val json = try {
                    JSONObject(data)
                } catch (_: Exception) {
                    continue
                }
                val error = json.optJSONObject("error")
                if (error != null) {
                    emit(LlmEvent.Error(error.optString("message").ifEmpty { data.take(300) }))
                    emittedError = true
                    break
                }
                val choices = json.optJSONArray("choices") ?: continue
                val choice = choices.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta") ?: continue
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    emit(LlmEvent.Text(content))
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
            b.endsWith("/chat/completions") -> b
            else -> "$b/chat/completions"
        }
    }

    companion object {
        private const val TAG = "ReadAskAi"
    }
}
