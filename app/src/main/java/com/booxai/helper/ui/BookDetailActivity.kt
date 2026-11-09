package com.booxai.helper.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxai.helper.App
import com.booxai.helper.R
import com.booxai.helper.databinding.ActivityBookDetailBinding
import kotlinx.coroutines.launch

class BookDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookDetailBinding
    private val bookId: Long by lazy { intent.getLongExtra(EXTRA_BOOK_ID, -1L) }

    private val adapter = SessionAdapter { summary ->
        startActivity(
            Intent(this, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, summary.session.id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (bookId <= 0) {
            finish()
            return
        }
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            val book = App.repository.book(bookId)
            binding.tvTitle.text = book?.title ?: getString(R.string.book_unknown)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.repository.observeSessions(bookId).collect { sessions ->
                    adapter.submitList(sessions)
                    binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
