package com.booxai.helper.ai

import com.booxai.helper.data.SecretStore
import com.booxai.helper.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ProviderRouter(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
) {

    fun currentConfig(): ProviderConfig? {
        val key = secrets.get(SecretStore.KEY_LLM)?.trim().orEmpty()
        val base = settings.baseUrl.trim()
        val model = settings.model.trim()
        if (key.isEmpty() || base.isEmpty() || model.isEmpty()) return null
        return ProviderConfig(
            protocol = settings.protocol,
            baseUrl = base,
            model = model,
            apiKey = key,
        )
    }

    fun client(config: ProviderConfig): LlmClient {
        return if (config.protocol == "anthropic") {
            AnthropicClient(config.baseUrl, config.apiKey)
        } else {
            OpenAiClient(config.baseUrl, config.apiKey)
        }
    }

    suspend fun test(config: ProviderConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = LlmRequest(
                model = config.model,
                system = "你是一个连接测试助手。只回复 OK。",
                messages = listOf(LlmMessage("user", "连接测试，请只回复 OK")),
                maxTokens = 16,
                temperature = 0.0,
            )
            var error: String? = null
            val text = StringBuilder()
            val completed = withTimeoutOrNull(25_000L) {
                client(config).stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.Text -> text.append(event.delta)
                        is LlmEvent.Error -> error = event.message
                        LlmEvent.Done -> Unit
                    }
                }
                true
            }
            when {
                completed == null -> Result.failure(Exception("请求超时（25 秒），请检查网络/Base URL"))
                error != null -> Result.failure(Exception(error))
                else -> Result.success(text.toString().trim().ifEmpty { "OK" })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
