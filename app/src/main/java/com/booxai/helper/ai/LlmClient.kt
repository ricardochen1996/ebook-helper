package com.booxai.helper.ai

import kotlinx.coroutines.flow.Flow

data class LlmImage(
    val base64: String,
    val mediaType: String = "image/jpeg",
)

data class LlmMessage(
    val role: String,
    val content: String,
    val image: LlmImage? = null,
)

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<LlmMessage>,
    val maxTokens: Int = 768,
    val temperature: Double = 0.7,
)

sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent
    data class Error(val message: String, val cause: Throwable? = null) : LlmEvent
    data object Done : LlmEvent
}

interface LlmClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

data class ProviderConfig(
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)
