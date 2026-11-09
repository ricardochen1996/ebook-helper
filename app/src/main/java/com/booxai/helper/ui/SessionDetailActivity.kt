package com.booxai.helper.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.booxai.helper.App
import com.booxai.helper.R
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
                Intent(this, PanelActivity::class.java)
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
        messages.forEach { message ->
            MessageRenderer.addMessage(
                binding.messagesContainer,
                message.role,
                message.content,
                message.createdAt,
                message.interrupted
            )
        }
        binding.scrollMessages.post {
            binding.scrollMessages.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
