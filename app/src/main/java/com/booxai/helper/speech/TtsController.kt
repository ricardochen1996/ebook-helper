package com.booxai.helper.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TtsController(context: Context) {

    enum class State { IDLE, INITIALIZING, READY, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val counter = AtomicInteger(0)
    private val pending = mutableListOf<Pair<String, Boolean>>()
    private val handler = Handler(Looper.getMainLooper())
    private val idleShutdown = Runnable { shutdown() }
    // 已入队但尚未播完的句子数（包含还没 onStart 的），用于判断队列真正播完。
    private val pendingUtterances = AtomicInteger(0)
    // 每次 QUEUE_FLUSH 换一代，旧一代的 onDone/onStop 不再影响新队列计数。
    private val generation = AtomicInteger(0)
    // 保护 generation + pendingUtterances 的复合操作（主线程与 TTS 回调线程）。
    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private var initialized = false
    @Volatile
    private var focusHeld = false
    private var lastInitAttemptAt = 0L
    // 引擎进程死亡等极端情况下可能收不到完成回调，兜底释放。
    private val forceIdle = Runnable {
        val stuck = synchronized(lock) {
            if (pendingUtterances.get() > 0) {
                generation.incrementAndGet()
                pendingUtterances.set(0)
                true
            } else {
                false
            }
        }
        if (stuck) {
            try {
                tts?.stop()
            } catch (_: Exception) {
            }
            abandonFocus()
            scheduleIdleShutdown()
        }
    }

    private val _state = MutableStateFlow(State.IDLE)
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

    /**
     * 按需初始化。App.onCreate 不再调用；第一次 speak() 时才绑定系统 TTS。
     */
    fun init() {
        if (initialized && _state.value != State.UNAVAILABLE) return
        val now = SystemClock.elapsedRealtime()
        if (lastInitAttemptAt != 0L && now - lastInitAttemptAt < INIT_RETRY_MS) return
        lastInitAttemptAt = now
        initialized = true
        _state.value = State.INITIALIZING
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
            override fun onStart(utteranceId: String?) {
                val gen = generationOf(utteranceId)
                if (gen != null && gen != generation.get()) return
                handler.removeCallbacks(idleShutdown)
            }

            override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) =
                onUtteranceFinished(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                onUtteranceFinished(utteranceId)
        })
        _state.value = State.READY
        lastError = ""
        flushPending()
        // 只在设置页初始化、还没有朗读时，也要有兜底释放，避免 TTS 常驻。
        if (pendingUtterances.get() == 0) {
            scheduleIdleShutdown()
        }
    }

    /**
     * 队列播完后再空闲一段时间就释放引擎，避免 Google TTS 进程常驻。
     */
    private fun generationOf(utteranceId: String?): Int? =
        utteranceId?.split('_')?.getOrNull(1)?.toIntOrNull()

    private fun onUtteranceFinished(utteranceId: String?) {
        val left = synchronized(lock) {
            // QUEUE_FLUSH 后旧队列的完成回调可能晚到，忽略非当前代。
            val gen = generationOf(utteranceId)
            if (gen != null && gen != generation.get()) return
            pendingUtterances.updateAndGet { if (it > 0) it - 1 else 0 }
        }
        if (left == 0) {
            handler.removeCallbacks(forceIdle)
            // 回调来自 TTS binder 线程，音频焦点操作回主线程做。
            handler.post {
                abandonFocus()
                scheduleIdleShutdown()
            }
        }
    }

    private fun scheduleIdleShutdown() {
        handler.removeCallbacks(idleShutdown)
        handler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS)
    }

    private fun fail(message: String) {
        lastError = message
        languageOk = false
        handler.removeCallbacks(forceIdle)
        synchronized(lock) {
            generation.incrementAndGet()
            pendingUtterances.set(0)
        }
        // 失败/不可用时释放旧引擎，避免重试时覆盖实例但旧绑定还活着。
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
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
        handler.removeCallbacks(idleShutdown)
        if (_state.value != State.READY || tts == null) {
            pending.add(clean to flush)
            if (pending.size > 20) pending.removeAt(0)
            init()
            return
        }
        val engine = tts ?: return
        requestFocus()
        val gen = if (flush) {
            synchronized(lock) {
                // QUEUE_FLUSH 会丢弃之前排队的内容，计数一并归零。
                generation.incrementAndGet().also { pendingUtterances.set(0) }
            }
        } else {
            generation.get()
        }
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
            // 先计数再入队，避免极快的 onDone/onError 先于计数到达。
            synchronized(lock) { pendingUtterances.incrementAndGet() }
            val result = try {
                engine.speak(
                    chunk,
                    mode,
                    Bundle(),
                    "readask_${gen}_${counter.incrementAndGet()}"
                )
            } catch (_: Exception) {
                TextToSpeech.ERROR
            }
            if (result != TextToSpeech.SUCCESS) {
                val empty = synchronized(lock) {
                    pendingUtterances.updateAndGet { if (it > 0) it - 1 else 0 } == 0
                }
                if (empty) {
                    // 引擎直接拒绝且队列为空，别把音频焦点一直占着。
                    abandonFocus()
                    scheduleIdleShutdown()
                }
            }
            first = false
        }
        if (pendingUtterances.get() > 0) {
            handler.removeCallbacks(forceIdle)
            handler.postDelayed(forceIdle, MAX_UTTERANCE_MS)
        }
    }

    fun stop() {
        pending.clear()
        handler.removeCallbacks(forceIdle)
        synchronized(lock) {
            generation.incrementAndGet()
            pendingUtterances.set(0)
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        abandonFocus()
        scheduleIdleShutdown()
    }

    fun shutdown() {
        handler.removeCallbacks(idleShutdown)
        handler.removeCallbacks(forceIdle)
        pending.clear()
        synchronized(lock) {
            generation.incrementAndGet()
            pendingUtterances.set(0)
        }
        abandonFocus()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        initialized = false
        lastInitAttemptAt = 0L
        _state.value = State.IDLE
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val items = pending.toList()
        pending.clear()
        items.forEach { (text, flush) -> speak(text, flush) }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        if (focusHeld) return
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
            focusHeld = manager.requestAudioFocus(focusRequest!!) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        if (!focusHeld) return
        focusHeld = false
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "ReadAskTts"
        private const val INIT_RETRY_MS = 5_000L
        private const val IDLE_TIMEOUT_MS = 180_000L
        private const val MAX_UTTERANCE_MS = 15 * 60_000L
    }
}
