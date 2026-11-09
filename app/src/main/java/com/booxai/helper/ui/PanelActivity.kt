package com.booxai.helper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.booxai.helper.App
import com.booxai.helper.BuildConfig
import com.booxai.helper.R
import com.booxai.helper.accessibility.WeReadExtractor
import com.booxai.helper.ai.LlmEvent
import com.booxai.helper.ai.LlmMessage
import com.booxai.helper.ai.LlmRequest
import com.booxai.helper.ai.PromptBuilder
import com.booxai.helper.data.ChatRepository
import com.booxai.helper.data.MessageEntity
import com.booxai.helper.databinding.ActivityPanelBinding
import com.booxai.helper.floating.FloatingBubbleService
import com.booxai.helper.speech.SentenceSegmenter
import com.booxai.helper.speech.onyx.OnyxSpeechClient
import com.booxai.helper.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelBinding

    private var pageText: String? = null
    private var bookTitle: String = ""
    private var bookId: Long = 0L
    private var sessionId: Long = -1L
    private var streamJob: Job? = null
    private var isStreaming = false
    private var expandedPage = false

    private lateinit var onyxSpeech: OnyxSpeechClient
    private var voiceSession = false
    private var voiceActive = false

    private val voicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                toast(getString(R.string.voice_permission_granted))
            } else {
                binding.btnVoice.text = getString(R.string.panel_voice)
                toast(getString(R.string.voice_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 浮在微信读书上方的小窗，而不是全屏 Activity。
        val metrics = resources.displayMetrics
        window.setLayout(
            (metrics.widthPixels * 0.88f).toInt(),
            (metrics.heightPixels * 0.72f).toInt()
        )
        window.setGravity(Gravity.CENTER)
        setFinishOnTouchOutside(true)
        FloatingBubbleService.setPanelOpen(true)

        pageText = intent.getStringExtra(EXTRA_PAGE_TEXT)
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (bookTitle.isBlank()) {
            bookTitle = App.settings.lastBookTitle
        }
        if (!WeReadExtractor.isPlausibleTitle(bookTitle)) {
            bookTitle = ""
        }

        binding.tvBookTitle.text = bookTitle.ifBlank { getString(R.string.book_unknown) }
        binding.btnStop.isEnabled = false
        updateTtsButton()
        updatePagePreview()

        binding.btnClose.setOnClickListener { closePanel() }
        binding.btnEditTitle.setOnClickListener { showTitleDialog() }
        binding.btnAsk.setOnClickListener { sendQuestion() }
        binding.btnStop.setOnClickListener { stopStreaming() }
        binding.btnNewSession.setOnClickListener { newSession() }
        binding.btnHistory.setOnClickListener { openHistory() }
        binding.btnTts.setOnClickListener { toggleTts() }
        binding.tvPageToggle.setOnClickListener { togglePage() }
        binding.tvPagePreview.setOnClickListener { togglePage() }
        binding.btnVoice.setOnClickListener {
            toast(getString(R.string.panel_voice_unavailable))
        }
        onyxSpeech = OnyxSpeechClient(this)
        setupVoice()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closePanel()
            }
        })

        lifecycleScope.launch {
            initSession()
            if (BuildConfig.DEBUG) {
                val debugQuestion = intent.getStringExtra(EXTRA_DEBUG_QUESTION)
                if (!debugQuestion.isNullOrBlank()) {
                    binding.etQuestion.setText(debugQuestion)
                    sendQuestion()
                }
            }
        }
    }

    /**
     * 悬浮球路径下这个 Activity 是独立任务；关闭时连任务一起移除，
     * 这样会直接回到微信读书，而不是 App 首页。
     */
    private fun closePanel() {
        if (isTaskRoot) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        if (::onyxSpeech.isInitialized) {
            onyxSpeech.release()
        }
        streamJob?.cancel()
        streamJob = null
        App.tts.stop()
        FloatingBubbleService.setPanelOpen(false)
        super.onDestroy()
    }

    private suspend fun initSession() {
        if (sessionId > 0) {
            val session = App.repository.session(sessionId)
            if (session != null) {
                bookId = session.bookId
                val book = App.repository.book(bookId)
                if (book != null) {
                    bookTitle = book.title
                    App.settings.lastBookTitle = book.title
                }
                if (pageText.isNullOrBlank()) {
                    pageText = App.repository.lastPageText(sessionId)
                }
            } else {
                sessionId = -1L
            }
        }
        if (sessionId <= 0) {
            var initialTitle = bookTitle.ifBlank { App.settings.lastBookTitle }
            if (!WeReadExtractor.isPlausibleTitle(initialTitle)) {
                initialTitle = ""
            }
            val book = App.repository.resolveBook(initialTitle)
            bookId = book.id
            bookTitle = book.title
            val session = App.repository.getOrCreateSession(bookId)
            sessionId = session.id
        }

        binding.tvBookTitle.text = bookTitle.ifBlank { getString(R.string.book_unknown) }
        updatePagePreview()
        val messages = App.repository.messages(sessionId)
        renderHistory(messages)
        binding.tvStatus.text = getString(R.string.panel_ready)
        if (App.router.currentConfig() == null) {
            binding.tvStatus.text = getString(R.string.panel_need_config)
        } else if (bookTitle.isBlank() || bookTitle == ChatRepository.UNKNOWN_TITLE) {
            binding.tvStatus.text = getString(R.string.panel_title_hint)
        }
    }

    private fun renderHistory(messages: List<MessageEntity>) {
        binding.messagesContainer.removeAllViews()
        if (messages.isEmpty()) {
            MessageRenderer.addHint(binding.messagesContainer, getString(R.string.panel_ask_first))
        } else {
            // 长会话只渲染最近的一部分，避免打开面板时一次性 inflate 上百条消息。
            val visible = if (messages.size > MAX_RENDERED_MESSAGES) {
                messages.takeLast(MAX_RENDERED_MESSAGES)
            } else {
                messages
            }
            if (visible.size < messages.size) {
                MessageRenderer.addHint(
                    binding.messagesContainer,
                    "仅显示最近 $MAX_RENDERED_MESSAGES 条消息，完整记录见书籍详情"
                )
            }
            visible.forEach { message ->
                MessageRenderer.addMessage(
                    binding.messagesContainer,
                    message.role,
                    message.content,
                    message.createdAt,
                    message.interrupted
                )
            }
        }
        scrollToBottom()
    }

    private fun sendQuestion() {
        if (isStreaming) return
        val question = binding.etQuestion.text.toString().trim()
        if (question.isEmpty()) return
        if (sessionId <= 0) {
            toast("正在准备会话，请稍候…")
            return
        }
        val config = App.router.currentConfig()
        if (config == null) {
            binding.tvStatus.text = getString(R.string.panel_need_config)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        binding.etQuestion.setText("")
        hideKeyboard()
        MessageRenderer.addMessage(
            binding.messagesContainer,
            ChatRepository.ROLE_USER,
            question,
            System.currentTimeMillis()
        )
        scrollToBottom()
        setStreaming(true)
        binding.tvStatus.text = getString(R.string.panel_thinking)

        val session = sessionId
        val book = bookTitle
        val page = pageText
        streamJob = lifecycleScope.launch {
            var assistantView: TextView? = null
            val full = StringBuilder()
            var failed = false
            var errorMessage: String? = null
            var saved = false
            try {
                val previous = withContext(Dispatchers.IO) {
                    App.repository.messages(session)
                }
                withContext(Dispatchers.IO) {
                    App.repository.addMessage(
                        session,
                        ChatRepository.ROLE_USER,
                        question,
                        page,
                        book,
                        config.protocol,
                        config.model
                    )
                }
                assistantView = MessageRenderer.addMessage(
                    binding.messagesContainer,
                    ChatRepository.ROLE_ASSISTANT,
                    "",
                    System.currentTimeMillis()
                )
                scrollToBottom()

                val system = PromptBuilder.system(
                    book.ifBlank { getString(R.string.book_unknown) },
                    page,
                    App.settings.extraPrompt
                )
                val request = LlmRequest(
                    model = config.model,
                    system = system,
                    messages = PromptBuilder.messages(
                        previous.map { LlmMessage(it.role, it.content) },
                        question
                    ),
                    maxTokens = App.settings.maxTokens,
                    temperature = App.settings.temperature.toDouble(),
                )

                val segmenter = SentenceSegmenter()
                var firstSentence = true
                var lastUiUpdate = 0L
                val start = SystemClock.uptimeMillis()

                App.router.client(config).stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.Text -> {
                            full.append(event.delta)
                            if (App.settings.ttsEnabled) {
                                segmenter.append(event.delta).forEach { sentence ->
                                    App.tts.speak(sentence, flush = firstSentence)
                                    firstSentence = false
                                }
                            }
                            val now = SystemClock.uptimeMillis()
                            if (now - lastUiUpdate >= STREAM_UI_INTERVAL_MS) {
                                assistantView?.text = full.toString()
                                lastUiUpdate = now
                                scrollToBottom()
                            }
                        }

                        is LlmEvent.Error -> {
                            failed = true
                            errorMessage = event.message
                            android.util.Log.w(
                                "ReadAskPanel",
                                "LLM error: ${event.message}",
                                event.cause
                            )
                        }

                        LlmEvent.Done -> Unit
                    }
                }

                if (App.settings.ttsEnabled) {
                    segmenter.flush()?.let { App.tts.speak(it) }
                }
                val finalText = full.toString().trim()
                assistantView?.text = if (finalText.isNotEmpty()) {
                    finalText
                } else if (failed) {
                    getString(R.string.panel_error, errorMessage.orEmpty())
                } else {
                    ""
                }

                val assistantContent = when {
                    finalText.isNotEmpty() -> finalText
                    failed -> "（请求失败：${errorMessage.orEmpty()}）"
                    else -> ""
                }
                if (assistantContent.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        App.repository.addMessage(
                            session,
                            ChatRepository.ROLE_ASSISTANT,
                            assistantContent,
                            null,
                            book,
                            config.protocol,
                            config.model,
                            interrupted = failed
                        )
                    }
                }
                saved = true
                if (failed) {
                    binding.tvStatus.text = getString(R.string.panel_error, errorMessage.orEmpty())
                } else {
                    val elapsed = SystemClock.uptimeMillis() - start
                    binding.tvStatus.text =
                        "完成 · ${TimeFormat.duration(elapsed)} · ${config.model}"
                }
                scrollToBottom()
            } catch (e: CancellationException) {
                val partial = full.toString().trim()
                assistantView?.text = if (partial.isEmpty()) {
                    getString(R.string.panel_interrupted)
                } else {
                    "$partial\n${getString(R.string.panel_interrupted)}"
                }
                if (partial.isNotEmpty()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        App.repository.addMessage(
                            session,
                            ChatRepository.ROLE_ASSISTANT,
                            partial,
                            null,
                            book,
                            config.protocol,
                            config.model,
                            interrupted = true
                        )
                    }
                }
                saved = true
                binding.tvStatus.text = getString(R.string.panel_interrupted)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                assistantView?.text = getString(R.string.panel_error, message)
                binding.tvStatus.text = getString(R.string.panel_error, message)
                if (!saved) {
                    withContext(Dispatchers.IO) {
                        App.repository.addMessage(
                            session,
                            ChatRepository.ROLE_ASSISTANT,
                            "（请求失败：$message）",
                            null,
                            book,
                            config.protocol,
                            config.model,
                            interrupted = true
                        )
                    }
                    saved = true
                }
                scrollToBottom()
            } finally {
                setStreaming(false)
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        App.tts.stop()
        setStreaming(false)
        binding.tvStatus.text = getString(R.string.panel_interrupted)
    }

    private fun setStreaming(value: Boolean) {
        isStreaming = value
        binding.btnAsk.isEnabled = !value
        binding.btnStop.isEnabled = value
        binding.btnNewSession.isEnabled = !value
        binding.btnHistory.isEnabled = !value
        binding.btnEditTitle.isEnabled = !value
        if (value) {
            binding.tvStatus.text = getString(R.string.panel_thinking)
        }
    }

    private fun newSession() {
        if (isStreaming || bookId <= 0) return
        App.tts.stop()
        lifecycleScope.launch {
            val session = App.repository.createSession(bookId)
            sessionId = session.id
            binding.messagesContainer.removeAllViews()
            MessageRenderer.addHint(binding.messagesContainer, getString(R.string.panel_ask_first))
            binding.tvStatus.text = "新会话"
        }
    }

    private fun openHistory() {
        if (bookId > 0) {
            startActivity(
                Intent(this, BookDetailActivity::class.java)
                    .putExtra(BookDetailActivity.EXTRA_BOOK_ID, bookId)
            )
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun toggleTts() {
        App.settings.ttsEnabled = !App.settings.ttsEnabled
        if (!App.settings.ttsEnabled) {
            App.tts.stop()
        }
        updateTtsButton()
    }

    private fun updateTtsButton() {
        binding.btnTts.text = getString(
            if (App.settings.ttsEnabled) R.string.panel_tts_on else R.string.panel_tts_off
        )
    }

    private fun togglePage() {
        expandedPage = !expandedPage
        updatePagePreview()
    }

    private fun updatePagePreview() {
        val text = pageText
        if (text.isNullOrBlank()) {
            binding.tvPagePreview.text = getString(R.string.panel_page_empty)
            binding.tvPagePreview.maxLines = 3
            binding.tvPageToggle.visibility = View.GONE
        } else {
            // 收起时只排版前 300 字。整页最多 4000 字，全部塞进 3 行 TextView
            // 会在开面板时产生一次不必要的全量文本测量。
            val shown = if (expandedPage || text.length <= PREVIEW_COLLAPSED_CHARS) {
                text
            } else {
                text.take(PREVIEW_COLLAPSED_CHARS) + "…"
            }
            binding.tvPagePreview.text = shown
            binding.tvPagePreview.maxLines = if (expandedPage) 30 else 3
            binding.tvPageToggle.visibility = View.VISIBLE
            binding.tvPageToggle.text = getString(
                if (expandedPage) R.string.panel_collapse else R.string.panel_expand
            )
        }
    }

    private fun showTitleDialog() {
        val edit = EditText(this).apply {
            setText(bookTitle)
            setSelection(text.length)
            hint = getString(R.string.book_title_hint)
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.book_title)
            .setMessage(R.string.book_title_help)
            .setView(edit)
            .setPositiveButton(R.string.book_title_save) { _, _ ->
                val newTitle = edit.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    updateBookTitle(newTitle)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateBookTitle(newTitle: String) {
        lifecycleScope.launch {
            val newBookId = App.repository.renameBook(bookId, newTitle)
            bookId = newBookId
            bookTitle = newTitle
            App.settings.lastBookTitle = newTitle
            binding.tvBookTitle.text = newTitle
            binding.tvStatus.text = "书名已更新"
        }
    }

    private fun setupVoice() {
        binding.btnVoice.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!voiceSession) {
                        if (ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            beginVoice()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (voiceActive) {
                        voiceActive = false
                        binding.btnVoice.text = getString(R.string.voice_processing)
                        binding.tvStatus.text = getString(R.string.voice_processing)
                        onyxSpeech.stop()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun beginVoice() {
        if (voiceSession) return
        voiceSession = true
        voiceActive = true
        binding.btnVoice.text = getString(R.string.voice_release_to_stop)
        binding.tvStatus.text = getString(R.string.voice_listening)
        onyxSpeech.start(object : OnyxSpeechClient.Listener {
            override fun onListening() {
                if (voiceSession) {
                    binding.tvStatus.text = getString(R.string.voice_listening)
                }
            }

            override fun onPartial(text: String) {
                if (!voiceSession) return
                binding.etQuestion.setText(text)
                binding.etQuestion.setSelection(text.length)
            }

            override fun onFinal(text: String) {
                voiceSession = false
                voiceActive = false
                binding.btnVoice.text = getString(R.string.panel_voice)
                if (text.isBlank()) {
                    binding.tvStatus.text = getString(R.string.voice_empty)
                } else {
                    binding.etQuestion.setText(text)
                    binding.etQuestion.setSelection(text.length)
                    binding.tvStatus.text = getString(R.string.voice_done)
                }
            }

            override fun onError(message: String) {
                voiceSession = false
                voiceActive = false
                binding.btnVoice.text = getString(R.string.panel_voice)
                binding.tvStatus.text =
                    "$message；${getString(R.string.voice_unavailable_hint)}"
                toast(message)
            }
        })
    }

    private fun scrollToBottom() {
        binding.scrollMessages.post {
            binding.scrollMessages.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun hideKeyboard() {
        try {
            val manager = getSystemService(InputMethodManager::class.java)
            manager.hideSoftInputFromWindow(binding.etQuestion.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PAGE_TEXT = "page_text"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_DEBUG_QUESTION = "debug_question"

        // 墨水屏刷新慢，流式文本 600ms 更新一次足够，也能明显减少整段 TextView 重排。
        private const val STREAM_UI_INTERVAL_MS = 600L
        private const val MAX_RENDERED_MESSAGES = 60
        private const val PREVIEW_COLLAPSED_CHARS = 300
    }
}
