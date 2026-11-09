package com.booxai.helper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.booxai.helper.App
import com.booxai.helper.R
import com.booxai.helper.data.ChatRepository
import com.booxai.helper.data.MessageEntity
import com.booxai.helper.databinding.ActivitySessionDetailBinding
import com.booxai.helper.util.TimeFormat
import kotlinx.coroutines.launch

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private val sessionId: Long by lazy { intent.getLongExtra(EXTRA_SESSION_ID, -1L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (sessionId <= 0) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnDeleteSession.setOnClickListener { confirmDeleteSession() }

        lifecycleScope.launch {
            val session = App.repository.session(sessionId)
            if (session != null) {
                val book = App.repository.book(session.bookId)
                val title = book?.title ?: getString(R.string.book_unknown)
                binding.tvTitle.text = "$title · ${TimeFormat.dateTime(session.startedAt)}"
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.repository.observeMessages(sessionId).collect { messages ->
                    render(messages)
                }
            }
        }

        binding.btnContinue.setOnClickListener {
            startActivity(
                Intent(this, SessionChatActivity::class.java)
                    .putExtra(PanelActivity.EXTRA_SESSION_ID, sessionId)
            )
        }
    }

    private fun render(messages: List<MessageEntity>) {
        binding.messagesContainer.removeAllViews()
        if (messages.isEmpty()) {
            MessageRenderer.addHint(binding.messagesContainer, "暂无消息")
            return
        }
        var lastQuestion: String? = null
        messages.forEach { message ->
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
        binding.scrollMessages.post {
            binding.scrollMessages.fullScroll(android.view.View.FOCUS_DOWN)
        }
        // 缩略图布局完成后内容高度会变化，再滚一次到最新消息。
        binding.scrollMessages.postDelayed({
            binding.scrollMessages.fullScroll(android.view.View.FOCUS_DOWN)
        }, 150L)
    }

    private fun confirmDeleteSession() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session)
            .setMessage(R.string.delete_session_confirm)
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                lifecycleScope.launch {
                    App.repository.deleteSession(sessionId)
                    toast(getString(R.string.deleted))
                    finish()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
