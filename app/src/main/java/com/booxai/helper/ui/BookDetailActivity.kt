package com.booxai.helper.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxai.helper.App
import com.booxai.helper.R
import com.booxai.helper.accessibility.ReadingState
import com.booxai.helper.databinding.ActivityBookDetailBinding
import kotlinx.coroutines.launch

class BookDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookDetailBinding
    private val bookId: Long by lazy { intent.getLongExtra(EXTRA_BOOK_ID, -1L) }

    private val adapter = SessionAdapter(
        onClick = { summary ->
            startActivity(
                Intent(this, SessionDetailActivity::class.java)
                    .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, summary.session.id)
            )
        },
        onDelete = { summary -> confirmDeleteSession(summary.session.id) },
    )

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
        binding.rvSessions.itemAnimator = null
        binding.btnBack.setOnClickListener { finish() }
        binding.btnDeleteBook.setOnClickListener { confirmDeleteBook() }

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

    private fun confirmDeleteSession(sessionId: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session)
            .setMessage(R.string.delete_session_confirm)
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                lifecycleScope.launch {
                    App.repository.deleteSession(sessionId)
                    toast(getString(R.string.deleted))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteBook() {
        lifecycleScope.launch {
            val book = App.repository.book(bookId) ?: return@launch
            AlertDialog.Builder(this@BookDetailActivity)
                .setTitle(R.string.delete_book)
                .setMessage(getString(R.string.delete_book_confirm, book.title))
                .setPositiveButton(R.string.dialog_delete) { _, _ ->
                    lifecycleScope.launch {
                        val deleted = App.repository.deleteBook(book.id)
                        if (deleted != null && ReadingState.lastBookTitle == deleted.title) {
                            ReadingState.lastBookTitle = null
                        }
                        toast(getString(R.string.deleted))
                        finish()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
