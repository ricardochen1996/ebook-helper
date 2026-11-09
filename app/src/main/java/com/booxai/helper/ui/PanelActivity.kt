package com.booxai.helper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
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
import com.booxai.helper.ai.LlmImage
import com.booxai.helper.ai.LlmMessage
import com.booxai.helper.ai.LlmRequest
import com.booxai.helper.ai.PromptBuilder
import com.booxai.helper.data.ChatRepository
import com.booxai.helper.data.MessageEntity
import com.booxai.helper.data.ScreenshotStore
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
import java.io.File

open class PanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelBinding

    /** 悬浮球打开时是小窗；从 App 里继续会话时用全屏聊天界面。 */
    protected open fun isFullScreen(): Boolean = false

    private var pageText: String? = null
    private var pageTextProvided = false
    private var screenshotPath: String? = null
    private var screenshotAvailable = false
    // 同一个面板里连续提问复用同一张截图，只持久化一次。
    private var storedScreenshotPath: String? = null
    private var storedScreenshotSource: String? = null
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
                setVoiceIdle()
                toast(getString(R.string.voice_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isFullScreen()) {
            // 浮在微信读书上方的小窗，而不是全屏 Activity。
            val metrics = resources.displayMetrics
            window.setLayout(
                (metrics.widthPixels * 0.78f).toInt(),
                (metrics.heightPixels * 0.62f).toInt()
            )
            window.setGravity(Gravity.CENTER)
            setFinishOnTouchOutside(true)
        }

        pageTextProvided = intent.hasExtra(EXTRA_PAGE_TEXT)
        pageText = intent.getStringExtra(EXTRA_PAGE_TEXT)
        screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        screenshotAvailable = screenshotPath?.let { path ->
            val file = File(path)
            file.isFile && file.length() > 0
        } == true
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (bookTitle.isBlank()) {
            bookTitle = App.settings.lastBookTitle
        }
        if (!WeReadExtractor.isPlausibleTitle(bookTitle)) {
            bookTitle = ""
        }

        binding.tvBookTitle.text = bookTitle.ifBlank { getString(R.string.book_unknown) }
        updateTtsButton()
        updatePagePreview()
        updateScreenshotUi()
        applyModeUi()

        binding.btnClose.setOnClickListener { closePanel() }
        binding.btnEditTitle.setOnClickListener { showTitleDialog() }
        binding.btnAsk.setOnClickListener {
            if (isStreaming) stopStreaming() else sendQuestion()
        }
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
     * 用 onStart/onStop 而不是 onCreate/onDestroy 同步悬浮球可见性：
     * 面板只是退到后台（按 Home、跳到设置/历史页）时也要把悬浮球放回来，
     * 否则通知栏还显示服务在运行，页面上却看不到悬浮球。
     *
     * 用 started 标记保证每个实例只增减一次：Activity 切换时旧面板的
     * onStop 可能晚于新面板的 onStart，服务端用计数处理这种重叠。
     */
    private var panelStarted = false

    override fun onStart() {
        super.onStart()
        if (!isFullScreen() && !panelStarted) {
            panelStarted = true
            FloatingBubbleService.onPanelStarted()
        }
    }

    override fun onStop() {
        if (!isFullScreen() && panelStarted) {
            panelStarted = false
            FloatingBubbleService.onPanelStopped()
        }
        super.onStop()
    }

    /**
     * singleTask 下再次打开面板可能复用当前实例而不是新建，此时必须
     * 用新 intent 里的书页/会话参数刷新界面，避免显示上一页的旧内容。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        streamJob?.cancel()
        streamJob = null
        setStreaming(false)
        App.tts.stop()
        voiceSession = false
        voiceActive = false
        binding.btnVoice.setImageResource(R.drawable.ic_mic)
        binding.btnVoice.contentDescription = getString(R.string.panel_voice)
        if (::onyxSpeech.isInitialized) {
            onyxSpeech.stop()
        }
        binding.etQuestion.setText("")
        expandedPage = false

        screenshotPath?.let {
            try {
                File(it).delete()
            } catch (_: Exception) {
            }
        }
        storedScreenshotPath = null
        storedScreenshotSource = null
        pageTextProvided = intent.hasExtra(EXTRA_PAGE_TEXT)
        pageText = intent.getStringExtra(EXTRA_PAGE_TEXT)
        screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        screenshotAvailable = screenshotPath?.let { path ->
            val file = File(path)
            file.isFile && file.length() > 0
        } == true
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (bookTitle.isBlank()) {
            bookTitle = App.settings.lastBookTitle
        }
        if (!WeReadExtractor.isPlausibleTitle(bookTitle)) {
            bookTitle = ""
        }

        binding.tvBookTitle.text = bookTitle.ifBlank { getString(R.string.book_unknown) }
        updateTtsButton()
        updatePagePreview()
        updateScreenshotUi()
        lifecycleScope.launch { initSession() }
    }

    /**
     * 悬浮球路径下这个 Activity 是独立任务；关闭时连任务一起移除，
     * 这样会直接回到微信读书，而不是 App 首页。
     */
    private fun applyModeUi() {
        if (!isFullScreen()) return
        binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.paper))
        binding.cardPage.visibility = View.GONE
        binding.rowScreenshot.visibility = View.GONE
        binding.cbScreenshot.visibility = View.GONE
        binding.tvScreenshotStatus.visibility = View.GONE
        binding.btnClose.setImageResource(R.drawable.ic_arrow_back)
        binding.btnClose.contentDescription = getString(R.string.back)
    }

    private fun closePanel() {
        if (isFullScreen()) {
            finish()
            return
        }
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
        // 兜底：onStop 通常已经减过了；没有走到 onStop 时在这里补一次。
        if (!isFullScreen() && panelStarted) {
            panelStarted = false
            FloatingBubbleService.onPanelStopped()
        }
        screenshotPath?.let {
            try {
                File(it).delete()
            } catch (_: Exception) {
            }
        }
        screenshotPath = null
        storedScreenshotPath = null
        storedScreenshotSource = null
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
                if (!isFullScreen() && !pageTextProvided && pageText.isNullOrBlank()) {
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
            MessageRenderer.addHint(
                binding.messagesContainer,
                getString(
                    if (isFullScreen()) R.string.panel_ask_first_chat else R.string.panel_ask_first
                )
            )
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
            var lastQuestion: String? = null
            visible.forEach { message ->
                if (message.role == ChatRepository.ROLE_USER) {
                    lastQuestion = message.content
                }
                val isUser = message.role == ChatRepository.ROLE_USER
                MessageRenderer.addMessage(
                    binding.messagesContainer,
                    message.role,
                    message.content,
                    message.createdAt,
                    message.interrupted,
                    question = if (isUser) null else lastQuestion,
                    pageText = if (isUser) message.pageText else null,
                    screenshotPath = if (isUser) message.screenshotPath else null,
                )
            }
        }
        // 缩略图在首次布局后才会撑开高度，补一次滚动确保最新消息完整可见。
        scrollToBottom()
        binding.scrollMessages.postDelayed({
            binding.scrollMessages.fullScroll(View.FOCUS_DOWN)
        }, 150L)
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
        val page = if (isFullScreen()) null else pageText
        val attachScreenshot =
            !isFullScreen() && binding.cbScreenshot.isChecked && screenshotAvailable
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
                val storedScreenshot = persistScreenshotForRecord(attachScreenshot)
                withContext(Dispatchers.IO) {
                    App.repository.addMessage(
                        session,
                        ChatRepository.ROLE_USER,
                        question,
                        page,
                        book,
                        config.protocol,
                        config.model,
                        screenshotPath = storedScreenshot,
                    )
                }
                assistantView = MessageRenderer.addMessage(
                    binding.messagesContainer,
                    ChatRepository.ROLE_ASSISTANT,
                    "",
                    System.currentTimeMillis(),
                    question = question,
                )
                scrollToBottom()

                val image = if (attachScreenshot) loadScreenshotImage() else null
                if (attachScreenshot && image == null) {
                    binding.tvScreenshotStatus.text =
                        getString(R.string.panel_screenshot_read_fail)
                }
                val history = previous.map { LlmMessage(it.role, it.content) }
                var useImage = image != null
                val start = SystemClock.uptimeMillis()
                while (true) {
                    val system = PromptBuilder.system(
                        book.ifBlank { getString(R.string.book_unknown) },
                        page,
                        App.settings.extraPrompt,
                        hasScreenshot = useImage
                    )
                    val request = LlmRequest(
                        model = config.model,
                        system = system,
                        messages = PromptBuilder.messages(
                            history,
                            question,
                            if (useImage) image else null
                        ),
                        maxTokens = App.settings.maxTokens,
                        temperature = App.settings.temperature.toDouble(),
                    )
                    val segmenter = SentenceSegmenter()
                    var firstSentence = true
                    var lastUiUpdate = 0L
                    var receivedText = false
                    var attemptError: String? = null
                    full.setLength(0)
                    assistantView?.text = ""
                    App.router.client(config).stream(request).collect { event ->
                        when (event) {
                            is LlmEvent.Text -> {
                                receivedText = true
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
                                attemptError = event.message
                                android.util.Log.w(
                                    "ReadAskPanel",
                                    "LLM error: ${event.message}",
                                    event.cause
                                )
                            }

                            LlmEvent.Done -> Unit
                        }
                    }
                    if (attemptError == null) {
                        if (App.settings.ttsEnabled) {
                            segmenter.flush()?.let { App.tts.speak(it) }
                        }
                        break
                    }
                    // 模型不支持图片时自动回退纯文字，避免图片页完全无法提问。
                    if (useImage && !receivedText) {
                        useImage = false
                        binding.tvStatus.text = getString(R.string.panel_screenshot_retry)
                        continue
                    }
                    failed = true
                    errorMessage = attemptError
                    break
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

    /**
     * 把本次提问的截图复制到 App 私有目录，并写进消息记录。
     * 同一面板内连续提问复用同一张截图，避免重复占空间。
     */
    private suspend fun persistScreenshotForRecord(attach: Boolean): String? {
        if (!attach) return null
        val source = screenshotPath ?: return null
        if (storedScreenshotSource == source && storedScreenshotPath != null) {
            return storedScreenshotPath
        }
        val stored = withContext(Dispatchers.IO) { ScreenshotStore.persist(source) }
        storedScreenshotSource = source
        storedScreenshotPath = stored
        return stored
    }

    private suspend fun loadScreenshotImage(): LlmImage? = withContext(Dispatchers.IO) {
        val path = screenshotPath ?: return@withContext null
        try {
            val bytes = File(path).readBytes()
            if (bytes.isEmpty()) {
                null
            } else {
                LlmImage(
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    mediaType = "image/jpeg"
                )
            }
        } catch (_: Exception) {
            null
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
        // 同一个按钮：空闲时发送，生成中变成停止。
        binding.btnAsk.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_send)
        binding.btnAsk.contentDescription = getString(
            if (value) R.string.panel_stop else R.string.panel_ask
        )
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
        val enabled = App.settings.ttsEnabled
        binding.btnTts.setImageResource(
            if (enabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        )
        binding.btnTts.contentDescription = getString(
            if (enabled) R.string.panel_tts_on else R.string.panel_tts_off
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
            binding.tvPagePreview.maxLines = 1
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
            binding.tvPagePreview.maxLines = if (expandedPage) 20 else 1
            binding.tvPageToggle.visibility = View.VISIBLE
            binding.tvPageToggle.text = getString(
                if (expandedPage) R.string.panel_collapse else R.string.panel_expand
            )
        }
    }

    private fun updateScreenshotUi() {
        if (isFullScreen()) {
            binding.rowScreenshot.visibility = View.GONE
            binding.cbScreenshot.visibility = View.GONE
            binding.tvScreenshotStatus.visibility = View.GONE
            return
        }
        binding.rowScreenshot.visibility = View.VISIBLE
        binding.cbScreenshot.visibility = if (screenshotAvailable) View.VISIBLE else View.GONE
        // 默认带上当前页截图，让视觉模型能直接看到图片/图表/版式；
        // 文字模型可手动取消，请求失败时也会自动回退纯文字。
        binding.cbScreenshot.isChecked = screenshotAvailable
        binding.tvScreenshotStatus.visibility = View.VISIBLE
        binding.tvScreenshotStatus.text = if (screenshotAvailable) {
            getString(R.string.panel_screenshot_ready)
        } else {
            getString(R.string.panel_screenshot_missing)
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
                        binding.btnVoice.contentDescription = getString(R.string.voice_processing)
                        binding.tvStatus.text = getString(R.string.voice_processing)
                        onyxSpeech.stop()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun setVoiceIdle() {
        binding.btnVoice.setImageResource(R.drawable.ic_mic)
        binding.btnVoice.contentDescription = getString(R.string.panel_voice)
    }

    private fun beginVoice() {
        if (voiceSession) return
        voiceSession = true
        voiceActive = true
        binding.btnVoice.setImageResource(R.drawable.ic_mic)
        binding.btnVoice.contentDescription = getString(R.string.voice_release_to_stop)
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
                setVoiceIdle()
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
                setVoiceIdle()
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
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"

        // 墨水屏刷新慢，流式文本 600ms 更新一次足够，也能明显减少整段 TextView 重排。
        private const val STREAM_UI_INTERVAL_MS = 600L
        private const val MAX_RENDERED_MESSAGES = 60
        private const val PREVIEW_COLLAPSED_CHARS = 300
    }
}
