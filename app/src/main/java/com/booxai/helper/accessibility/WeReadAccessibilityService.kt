package com.booxai.helper.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.booxai.helper.App

class WeReadAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ReadingState.serviceConnected = true
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
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
        try {
            val wins = windows ?: return
            val page = WeReadExtractor.extractPageTextFromWindows(wins)
            if (!page.isNullOrBlank()) {
                ReadingState.lastPageText = page
                ReadingState.lastCaptureAt = System.currentTimeMillis()
            }
            if (App.settings.autoTitle) {
                val title = WeReadExtractor.extractBookTitle(wins)
                if (!title.isNullOrBlank()) {
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
        val page = WeReadExtractor.extractPageTextFromWindows(wins) ?: return null
        if (page.isBlank()) return null
        val title = if (App.settings.autoTitle) {
            WeReadExtractor.extractBookTitle(wins)
        } else {
            null
        } ?: ReadingState.lastBookTitle
        if (!title.isNullOrBlank()) {
            ReadingState.lastBookTitle = title
            App.settings.lastBookTitle = title
        }
        ReadingState.lastPageText = page
        ReadingState.lastCaptureAt = System.currentTimeMillis()
        return CaptureResult(page, title, System.currentTimeMillis())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        ReadingState.serviceConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        ReadingState.serviceConnected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReadAskA11y"

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
