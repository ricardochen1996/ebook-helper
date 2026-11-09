package com.booxai.helper.ai

data class ProviderPreset(
    val id: String,
    val label: String,
    val protocol: String,
    val baseUrl: String,
    val model: String,
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com", "deepseek-chat"),
        ProviderPreset(
            "qwen",
            "通义千问（DashScope 兼容）",
            "openai",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-plus"
        ),
        ProviderPreset("moonshot", "Kimi（Moonshot）", "openai", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        ProviderPreset("zhipu", "智谱 GLM", "openai", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        ProviderPreset("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-mini"),
        ProviderPreset(
            "anthropic",
            "Anthropic Claude",
            "anthropic",
            "https://api.anthropic.com",
            "claude-sonnet-5-5"
        ),
        ProviderPreset("custom", "自定义 / 中转站", "openai", "", ""),
    )

    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id } ?: all.last()

    fun idOf(preset: ProviderPreset): Int = all.indexOf(preset).coerceAtLeast(0)
}
