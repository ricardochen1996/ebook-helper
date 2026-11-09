package com.booxai.helper.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TtsController(context: Context) {

    enum class State { INITIALIZING, READY, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val counter = AtomicInteger(0)
    private val pending = mutableListOf<Pair<String, Boolean>>()

    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private var initialized = false

    private val _state = MutableStateFlow(State.INITIALIZING)
    val state: StateFlow<State> = _state

    @Volatile
    var languageOk: Boolean = false
        private set

    @Volatile
    var engineLabel: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var rate: Float = 1.0f
        private set

    fun init() {
        if (initialized) return
        initialized = true
        createEngine("com.google.android.tts", 0)
    }

    private fun createEngine(engine: String?, attempt: Int) {
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engineObject = tts
                if (engineObject != null) {
                    setup(engineObject)
                } else {
                    fail("TTS 引擎为空")
                }
            } else if (attempt == 0) {
                try {
                    tts?.shutdown()
                } catch (_: Exception) {
                }
                tts = null
                createEngine(null, 1)
            } else {
                fail("TTS 初始化失败（status=$status）")
            }
        }
        tts = try {
            if (engine != null) TextToSpeech(appContext, listener, engine)
            else TextToSpeech(appContext, listener)
        } catch (e: Exception) {
            Log.w(TAG, "create TTS failed", e)
            fail("无法创建 TTS：${e.message}")
            null
        }
    }

    private fun setup(engine: TextToSpeech) {
        var result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.CHINESE)
        }
        languageOk = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!languageOk) {
            fail("中文语音不可用（请安装/切换 TTS 引擎）")
            return
        }
        try {
            val voices = engine.voices?.filter { it.locale.language == "zh" }.orEmpty()
            val preferred = voices.firstOrNull { it.locale.country == "CN" } ?: voices.firstOrNull()
            if (preferred != null) {
                engine.voice = preferred
            }
            engineLabel = buildString {
                append(engine.defaultEngine ?: "default")
                if (preferred != null) {
                    append(" / ")
                    append(preferred.name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice selection failed", e)
            engineLabel = engine.defaultEngine ?: "default"
        }
        engine.setSpeechRate(rate)
        engine.setPitch(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "utterance error: $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "utterance error: $utteranceId code=$errorCode")
            }
        })
        _state.value = State.READY
        lastError = ""
        flushPending()
    }

    private fun fail(message: String) {
        lastError = message
        languageOk = false
        _state.value = State.UNAVAILABLE
        Log.w(TAG, message)
    }

    fun setRate(value: Float) {
        rate = value.coerceIn(0.5f, 1.5f)
        if (_state.value == State.READY) {
            tts?.setSpeechRate(rate)
        }
    }

    /**
     * 朗读一段文本。flush=true 会打断当前队列（用于新回答的第一句）。
     */
    fun speak(text: String, flush: Boolean = false) {
        val clean = SpeechSanitizer.sanitize(text)
        if (clean.isBlank()) return
        if (_state.value != State.READY) {
            pending.add(clean to flush)
            if (pending.size > 20) pending.removeAt(0)
            init()
            return
        }
        val engine = tts ?: return
        requestFocus()
        val max = 3000
        var remaining = clean
        var first = true
        while (remaining.isNotEmpty()) {
            val chunk = if (remaining.length <= max) remaining else {
                val cut = remaining.lastIndexOfAny(charArrayOf('。', '！', '？', '\n'), max)
                    .let { if (it > 200) it + 1 else max }
                remaining.substring(0, cut)
            }
            remaining = remaining.substring(chunk.length)
            val mode = if (flush && first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, mode, Bundle(), "readask_${counter.incrementAndGet()}")
            first = false
        }
    }

    fun stop() {
        pending.clear()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        abandonFocus()
    }

    fun shutdown() {
        pending.clear()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        initialized = false
        abandonFocus()
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val items = pending.toList()
        pending.clear()
        items.forEach { (text, flush) -> speak(text, flush) }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
        }
        try {
            manager.requestAudioFocus(focusRequest!!)
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "ReadAskTts"
    }
}
