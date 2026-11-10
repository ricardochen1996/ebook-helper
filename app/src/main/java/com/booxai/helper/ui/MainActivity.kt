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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxai.helper.App
import com.booxai.helper.BuildConfig
import com.booxai.helper.R
import com.booxai.helper.accessibility.AccessibilityUtil
import com.booxai.helper.accessibility.ReadingState
import com.booxai.helper.data.BookEntity
import com.booxai.helper.databinding.ActivityMainBinding
import com.booxai.helper.floating.FloatingBubbleService
import com.booxai.helper.update.UpdateCheckResult
import com.booxai.helper.update.UpdateDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 自动检查间隔：成功后 24 小时内不再请求；失败后 1 小时重试，避免一次断网停摆一天
private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val UPDATE_FAILURE_RETRY_MS = 60L * 60 * 1000

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var apiConfigured = false
    private var autoUpdateInFlight = false
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            if (binding.cardSetup.visibility == View.VISIBLE) {
                statusHandler.postDelayed(this, 1500L)
            }
        }
    }

    private val adapter = BookAdapter(
        onClick = { book ->
            startActivity(
                Intent(this, BookDetailActivity::class.java)
                    .putExtra(BookDetailActivity.EXTRA_BOOK_ID, book.id)
            )
        },
        onDelete = { book -> confirmDeleteBook(book) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvBooks.layoutManager = LinearLayoutManager(this)
        binding.rvBooks.adapter = adapter
        // 墨水屏上列表动画只会带来多余刷新。
        binding.rvBooks.itemAnimator = null

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnApi.setOnClickListener {
            startActivity(Intent(this, QrConfigActivity::class.java))
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
        autoCheckUpdates()
    }

    private fun autoCheckUpdates() {
        // 调试包不是正式签名版本，更新提示只会干扰联调
        if (BuildConfig.DEBUG) return
        if (!App.settings.autoCheckUpdates) return
        val interval = if (App.settings.lastUpdateCheckFailed) {
            UPDATE_FAILURE_RETRY_MS
        } else {
            UPDATE_CHECK_INTERVAL_MS
        }
        if (System.currentTimeMillis() - App.settings.lastUpdateCheckAt < interval) {
            return
        }
        // 防止一次检查还没结束时 onResume 又发起一次；节流交给时间戳
        if (autoUpdateInFlight) return
        autoUpdateInFlight = true
        lifecycleScope.launch {
            try {
                // 检查在后台线程进行，不阻塞首页渲染
                val result = withContext(Dispatchers.IO) { App.updates.check() }
                val now = System.currentTimeMillis()
                when (result) {
                    is UpdateCheckResult.Available -> {
                        App.settings.lastUpdateCheckFailed = false
                        // 不在前台时先不推进时间戳：下次 onResume 再检查并提示，
                        // 避免这次更新提示被静默吞掉。
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            App.settings.lastUpdateCheckAt = now
                            UpdateDialogs.showAvailable(this@MainActivity, result.info)
                        }
                    }
                    UpdateCheckResult.UpToDate -> {
                        App.settings.lastUpdateCheckAt = now
                        App.settings.lastUpdateCheckFailed = false
                    }
                    is UpdateCheckResult.Failed -> {
                        App.settings.lastUpdateCheckAt = now
                        App.settings.lastUpdateCheckFailed = true
                    }
                }
            } finally {
                autoUpdateInFlight = false
            }
        }
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

    private fun confirmDeleteBook(book: BookEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_book)
            .setMessage(getString(R.string.delete_book_confirm, book.title))
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                lifecycleScope.launch {
                    val deleted = App.repository.deleteBook(book.id)
                    if (deleted != null && ReadingState.lastBookTitle == deleted.title) {
                        ReadingState.lastBookTitle = null
                    }
                    toast(getString(R.string.deleted))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
