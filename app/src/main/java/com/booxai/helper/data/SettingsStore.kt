package com.booxai.helper.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var protocol: String
        get() = prefs.getString(KEY_PROTOCOL, "openai") ?: "openai"
        set(value) = prefs.edit().putString(KEY_PROTOCOL, value).apply()

    var presetId: String
        get() = prefs.getString(KEY_PRESET, "deepseek") ?: "deepseek"
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "https://api.deepseek.com") ?: ""
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "deepseek-chat") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var ttsEnabled: Boolean
        // 默认关闭朗读，避免第一次提问就出声；需要时在面板或设置里手动开启。
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_RATE, value).apply()

    var autoTitle: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TITLE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TITLE, value).apply()

    var extraPrompt: String
        get() = prefs.getString(KEY_EXTRA_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTRA_PROMPT, value).apply()

    var autoCheckUpdates: Boolean
        // 默认自动检查：检查只拉取几 KB 的元数据，不会打断阅读
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply()

    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, value).apply()

    // 失败后只等 1 小时就重试，避免一次断网让自动检查停摆 24 小时
    var lastUpdateCheckFailed: Boolean
        get() = prefs.getBoolean(KEY_LAST_UPDATE_CHECK_FAILED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_UPDATE_CHECK_FAILED, value).apply()

    var lastBookTitle: String
        get() = prefs.getString(KEY_LAST_BOOK_TITLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_BOOK_TITLE, value).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 768)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value).apply()

    companion object {
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_PRESET = "preset"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_AUTO_TITLE = "auto_title"
        private const val KEY_EXTRA_PROMPT = "extra_prompt"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
        private const val KEY_LAST_UPDATE_CHECK_FAILED = "last_update_check_failed"
        private const val KEY_LAST_BOOK_TITLE = "last_book_title"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
    }
}
