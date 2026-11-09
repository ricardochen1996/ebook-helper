package com.booxai.helper.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.booxai.helper.App

class WeReadAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val extractRunnable = Runnable { extractLatest() }
    private var titleCheckPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ReadingState.serviceConnected = true
        if (ReadingState.lastBookTitle.isNullOrBlank()) {
            ReadingState.lastBookTitle = App.settings.lastBookTitle.takeIf { it.isNotBlank() }
        }
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != WeReadExtractor.PKG) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && App.settings.autoTitle) {
            titleCheckPending = true
        }
        // 有新事件时先让悬浮球缓存失效；等去抖后的 extractLatest 完成再恢复，
        // 避免翻页事件刚到、还没重新抓取时复用上一页正文。
        ReadingState.lastCaptureAt = 0L
        // 微信读书一次翻页/目录动画会连续发很多事件。
        // 合并成一次树遍历，避免主线程被无障碍查询拖住。
        handler.removeCallbacks(extractRunnable)
        handler.postDelayed(extractRunnable, EVENT_DEBOUNCE_MS)
    }

    private fun extractLatest() {
        val wins = windows ?: return
        val checkTitle = titleCheckPending
        // 首次书名还没缓存时，内容变化也继续尝试识别，直到目录面板被打开过一次。
        val wantTitle = App.settings.autoTitle &&
            (checkTitle || ReadingState.lastBookTitle.isNullOrBlank())
        if (!wantTitle) {
            titleCheckPending = false
        }
        try {
            val result = WeReadExtractor.extractFromWindows(wins, wantTitle)
            titleCheckPending = false
            val page = result.pageText
            if (!page.isNullOrBlank()) {
                if (page != ReadingState.lastPageText) {
                    ReadingState.lastPageText = page
                }
                // 即使正文没变，也刷新抓取时间，让悬浮球点击可以复用缓存。
                ReadingState.lastCaptureAt = System.currentTimeMillis()
            }
            if (wantTitle) {
                val title = result.bookTitle
                if (!title.isNullOrBlank() && title != ReadingState.lastBookTitle) {
                    ReadingState.lastBookTitle = title
                    App.settings.lastBookTitle = title
                    Log.i(TAG, "book title detected: $title")
                }
            }
        } catch (_: Exception) {
            // 无障碍树在窗口切换时可能短暂不可用，忽略即可
        }
    }

    fun capture(): CaptureResult? {
        val wins = windows ?: return null
        val result = try {
            WeReadExtractor.extractFromWindows(wins, wantTitle = App.settings.autoTitle)
        } catch (_: Exception) {
            null
        } ?: return null
        val page = result.pageText ?: return null
        if (page.isBlank()) return null
        val title = result.bookTitle ?: ReadingState.lastBookTitle
        if (!title.isNullOrBlank() && title != ReadingState.lastBookTitle) {
            ReadingState.lastBookTitle = title
            App.settings.lastBookTitle = title
        }
        ReadingState.lastPageText = page
        ReadingState.lastCaptureAt = System.currentTimeMillis()
        return CaptureResult(page, title, System.currentTimeMillis())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(extractRunnable)
        instance = null
        ReadingState.serviceConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(extractRunnable)
        instance = null
        ReadingState.serviceConnected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReadAskA11y"
        private const val EVENT_DEBOUNCE_MS = 300L

        @Volatile
        var instance: WeReadAccessibilityService? = null
            private set
    }
}

object AccessibilityUtil {
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, WeReadAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { part ->
            ComponentName.unflattenFromString(part) == expected
        }
    }
}
