package com.booxai.helper.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxai.helper.App
import com.booxai.helper.R
import com.booxai.helper.accessibility.AccessibilityUtil
import com.booxai.helper.accessibility.ReadingState
import com.booxai.helper.databinding.ActivityMainBinding
import com.booxai.helper.floating.FloatingBubbleService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var apiConfigured = false
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            if (binding.cardSetup.visibility == View.VISIBLE) {
                statusHandler.postDelayed(this, 1500L)
            }
        }
    }

    private val adapter = BookAdapter { book ->
        startActivity(
            Intent(this, BookDetailActivity::class.java)
                .putExtra(BookDetailActivity.EXTRA_BOOK_ID, book.id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvBooks.layoutManager = LinearLayoutManager(this)
        binding.rvBooks.adapter = adapter

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnApi.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnStartBubble.setOnClickListener { startBubble() }
        binding.btnBubbleReady.setOnClickListener { startBubble() }
        binding.btnOpenPanel.setOnClickListener {
            startActivity(
                Intent(this, PanelActivity::class.java).apply {
                    putExtra(PanelActivity.EXTRA_PAGE_TEXT, "（语音测试页面，可直接按住说话）")
                    putExtra(PanelActivity.EXTRA_BOOK_TITLE, "语音测试")
                }
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.repository.observeBooks().collect { books ->
                    adapter.submitList(books)
                    binding.tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 只在回到首页时解密一次 API Key；否则 1.5s 轮询会反复走 Android Keystore。
        apiConfigured = App.router.currentConfig() != null
        refreshStatus()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.postDelayed(statusRunnable, 1500L)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRunnable)
        super.onPause()
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val a11yEnabled = AccessibilityUtil.isServiceEnabled(this)
        val a11yConnected = ReadingState.serviceConnected
        val api = apiConfigured

        setTextIfChanged(
            binding.tvOverlayStatus,
            getString(if (overlay) R.string.setup_overlay_ok else R.string.setup_overlay_missing)
        )
        setTextIfChanged(
            binding.tvA11yStatus,
            when {
                a11yConnected -> getString(R.string.setup_a11y_ok)
                a11yEnabled -> getString(R.string.setup_a11y_not_connected)
                else -> getString(R.string.setup_a11y_missing)
            }
        )
        setTextIfChanged(
            binding.tvApiStatus,
            getString(if (api) R.string.setup_api_ok else R.string.setup_api_missing)
        )
        binding.btnOverlay.isEnabled = !overlay
        binding.btnA11y.isEnabled = !a11yConnected
        binding.btnApi.isEnabled = !api

        val ready = overlay && a11yConnected && api
        val setupVisibility = if (ready) View.GONE else View.VISIBLE
        val readyVisibility = if (ready) View.VISIBLE else View.GONE
        if (binding.cardSetup.visibility != setupVisibility) {
            binding.cardSetup.visibility = setupVisibility
        }
        if (binding.cardReady.visibility != readyVisibility) {
            binding.cardReady.visibility = readyVisibility
        }
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) {
            view.text = text
        }
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_no_overlay))
            openOverlaySettings()
            return
        }
        if (!AccessibilityUtil.isServiceEnabled(this)) {
            toast(getString(R.string.toast_no_a11y))
            openAccessibilitySettings()
            return
        }
        if (!ReadingState.serviceConnected) {
            toast(getString(R.string.setup_a11y_not_connected))
            openAccessibilitySettings()
            return
        }
        FloatingBubbleService.start(this)
        toast(getString(R.string.toast_bubble_started))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
