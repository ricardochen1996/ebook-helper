package com.booxai.helper.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.booxai.helper.R
import com.booxai.helper.accessibility.CaptureResult
import com.booxai.helper.accessibility.ReadingState
import com.booxai.helper.accessibility.WeReadAccessibilityService
import com.booxai.helper.ui.MainActivity
import com.booxai.helper.ui.PanelActivity
import java.io.File
import java.io.FileOutputStream

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private val prefs by lazy { getSharedPreferences("bubble", Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var longPressRunnable: Runnable? = null
    private var lastNotificationMode: NotificationMode? = null
    private var captureInProgress = false
    private var serviceDestroyed = false
    private var screenshotDelayRunnable: Runnable? = null
    private var screenshotFallbackRunnable: Runnable? = null

    /**
     * 兜底看门狗：系统/冻结/状态错乱都可能让悬浮窗被移除或一直 GONE，
     * 而前台服务仍然活着（通知栏显示开着）。定时把悬浮球状态拉回一致。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            refreshBubbleState()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val panelClosedRunnable = Runnable { refreshBubbleState() }

    /**
     * 点悬浮球后、PanelActivity.onStart 之前的这段时间也属于“面板打开中”：
     * 悬浮球必须保持隐藏，否则可能被截进当前页或挡住面板。等不到面板就
     * 超时恢复悬浮球，避免启动失败后一直不可见。
     */
    private val panelStartGuardRunnable = Runnable {
        captureInProgress = false
        refreshBubbleState()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        lastNotificationMode = currentNotificationMode()
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_no_overlay))
            stopSelf()
            return
        }
        refreshBubbleState()
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 服务已经在运行时再次点「启动悬浮球」不会走 onCreate，
        // 这里补一次状态检查，让丢失的悬浮球能重新挂出来。
        refreshBubbleState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        serviceDestroyed = true
        captureInProgress = false
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(panelClosedRunnable)
        mainHandler.removeCallbacks(panelStartGuardRunnable)
        screenshotDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        screenshotFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        screenshotDelayRunnable = null
        screenshotFallbackRunnable = null
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        removeBubbleView()
        lastNotificationMode = null
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private enum class NotificationMode { NORMAL, PANEL_HIDDEN }

    private fun currentNotificationMode(): NotificationMode =
        if (panelOpen) NotificationMode.PANEL_HIDDEN else NotificationMode.NORMAL

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    if (panelOpen) {
                        R.string.notification_text_panel
                    } else {
                        R.string.notification_text
                    }
                )
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun updateNotificationIfNeeded() {
        val mode = currentNotificationMode()
        if (mode == lastNotificationMode) return
        lastNotificationMode = mode
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return
        val size = dp(BUBBLE_SIZE_DP)
        val bounds = windowBounds()
        val defaultX = (bounds.width() - size - dp(10)).coerceAtLeast(dp(10))
        val defaultY = (bounds.height() / 2).coerceAtLeast(dp(80))

        val view = TextView(this).apply {
            text = getString(R.string.bubble_label)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_text))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@FloatingBubbleService, R.drawable.bg_bubble)
            isClickable = true
            isFocusable = false
            contentDescription = getString(R.string.app_name)
        }
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, defaultX)
                .coerceIn(0, (bounds.width() - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, defaultY)
                .coerceIn(0, (bounds.height() - size).coerceAtLeast(0))
        }
        view.setOnTouchListener(BubbleTouchListener())
        try {
            windowManager.addView(view, layoutParams)
            bubbleView = view
            params = layoutParams
            if (panelOpen) view.visibility = View.GONE
        } catch (_: Exception) {
            toast(getString(R.string.toast_no_overlay))
            stopSelf()
        }
    }

    private fun removeBubbleView() {
        val view = bubbleView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        bubbleView = null
        params = null
    }

    /**
     * 让悬浮球与实际状态保持一致：该显示时显示，该隐藏时隐藏，
     * 被系统移除时重新挂载，位置跑出屏幕时拉回来。
     */
    private fun refreshBubbleState() {
        if (serviceDestroyed) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission unavailable, stopping service")
            stopSelf()
            return
        }
        if (panelOpen) {
            setBubbleVisible(false)
            return
        }
        // 点击后到面板真正打开之间的隐藏窗口不能被看门狗打断。
        if (captureInProgress) {
            setBubbleVisible(false)
            return
        }
        val view = bubbleView
        if (view == null) {
            showBubble()
            return
        }
        if (!view.isAttachedToWindow) {
            Log.w(TAG, "bubble detached from window manager, re-adding")
            removeBubbleView()
            showBubble()
            return
        }
        setBubbleVisible(true)
        clampBubblePosition()
    }

    private fun clampBubblePosition() {
        val view = bubbleView ?: return
        val layoutParams = params ?: return
        val bounds = windowBounds()
        val width = view.width.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)
        val height = view.height.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)
        val x = layoutParams.x.coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
        val y = layoutParams.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
        if (x != layoutParams.x || y != layoutParams.y) {
            layoutParams.x = x
            layoutParams.y = y
            try {
                windowManager.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {
            }
        }
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressFired = false
        private var lastDragUpdateAt = 0L
        private val touchSlop = dp(8)

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layoutParams = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    moved = false
                    longPressFired = false
                    lastDragUpdateAt = 0L
                    scheduleLongPress()
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                        cancelLongPress()
                    }
                    if (moved && event.eventTime - lastDragUpdateAt >= DRAG_UPDATE_INTERVAL_MS) {
                        lastDragUpdateAt = event.eventTime
                        val bounds = windowBounds()
                        layoutParams.x = (startX + dx.toInt()).coerceIn(0, bounds.width() - view.width)
                        layoutParams.y = (startY + dy.toInt()).coerceIn(0, bounds.height() - view.height)
                        try {
                            windowManager.updateViewLayout(view, layoutParams)
                        } catch (_: Exception) {
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    if (!moved && !longPressFired) {
                        onBubbleTap()
                    } else {
                        snapToEdge()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    snapToEdge()
                }
            }
            return true
        }

        private fun scheduleLongPress() {
            val runnable = Runnable {
                if (!moved) {
                    longPressFired = true
                    startActivity(Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            longPressRunnable = runnable
            mainHandler.postDelayed(runnable, 650L)
        }

        private fun cancelLongPress() {
            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            longPressRunnable = null
        }
    }

    private fun onBubbleTap() {
        if (panelOpen || captureInProgress) return
        val service = WeReadAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.toast_no_a11y))
            return
        }
        if (!service.isWeReadForeground()) {
            toast(getString(R.string.toast_not_weread))
            return
        }
        // 页面内容变化时服务已经缓存了正文；2 秒内直接复用，避免点击时
        // 再同步遍历整棵无障碍树，把面板打开拖慢。
        val now = System.currentTimeMillis()
        val cachedPage = ReadingState.lastPageText
        val cacheFresh = !cachedPage.isNullOrBlank() &&
            now - ReadingState.lastCaptureAt in 0..2_000L
        val capture = if (cacheFresh) {
            CaptureResult(cachedPage!!, ReadingState.lastBookTitle, ReadingState.lastCaptureAt)
        } else {
            try {
                service.capture()
            } catch (_: Exception) {
                null
            }
        }

        // 先隐藏悬浮球，再截屏，避免把球本身截进去。captureInProgress
        // 让看门狗在这段时间内不要又把球挂出来。
        captureInProgress = true
        setBubbleVisible(false)
        // 刚翻页时无障碍树通常比墨水屏先更新，等页面刷新稳定后再截屏，
        // 否则会出现“文字是下一页、截图还是上一页”的错位。
        val sincePageEvent = System.currentTimeMillis() - ReadingState.lastPageEventAt
        val settleDelay = if (ReadingState.lastPageEventAt > 0 &&
            sincePageEvent in 0..PAGE_SETTLE_MS
        ) {
            PAGE_SETTLE_MS - sincePageEvent
        } else {
            0L
        }
        val screenshotDelay = SCREENSHOT_DELAY_MS + settleDelay
        var finished = false
        val fallback = Runnable {
            screenshotFallbackRunnable = null
            if (finished || serviceDestroyed) return@Runnable
            finished = true
            if (capture == null || capture.pageText.isBlank()) {
                captureInProgress = false
                setBubbleVisible(true)
                toast(getString(R.string.toast_not_weread))
            } else {
                // 截图卡住时不要连面板一起卡住，先用文字打开。
                openPanel(capture, null)
            }
        }
        screenshotFallbackRunnable = fallback
        mainHandler.postDelayed(fallback, SCREENSHOT_TIMEOUT_MS)
        val takeScreenshotRunnable = Runnable {
            screenshotDelayRunnable = null
            if (serviceDestroyed) return@Runnable
            try {
                service.takeScreenshot { bitmap ->
                    if (finished || serviceDestroyed) {
                        bitmap?.recycle()
                        return@takeScreenshot
                    }
                    finished = true
                    mainHandler.removeCallbacks(fallback)
                    screenshotFallbackRunnable = null
                    val screenshotPath = bitmap?.let { saveScreenshot(it) }
                    mainHandler.post {
                        if (serviceDestroyed) {
                            screenshotPath?.let { File(it).delete() }
                            return@post
                        }
                        // 截图完成后再抓一次正文，确保文字和截图来自同一页；
                        // 微信读书预加载相邻页时，点击前的缓存可能是别的页。
                        val freshCapture = try {
                            service.capture()
                        } catch (_: Exception) {
                            null
                        }
                        val finalCapture = freshCapture
                            ?.takeIf { it.pageText.isNotBlank() }
                            ?: capture
                        if ((finalCapture == null || finalCapture.pageText.isBlank()) && screenshotPath == null) {
                            // 既没有文字也没有截图，说明当前不是可读页面。
                            captureInProgress = false
                            setBubbleVisible(true)
                            toast(getString(R.string.toast_not_weread))
                            return@post
                        }
                        openPanel(finalCapture, screenshotPath)
                    }
                }
            } catch (_: Exception) {
                fallback.run()
            }
        }
        screenshotDelayRunnable = takeScreenshotRunnable
        mainHandler.postDelayed(takeScreenshotRunnable, screenshotDelay)
    }

    private fun openPanel(capture: CaptureResult?, screenshotPath: String?) {
        if (serviceDestroyed) return
        try {
            startActivity(Intent(this, PanelActivity::class.java).apply {
                // 独立任务 + 独立 taskAffinity：关闭面板时直接回到微信读书，
                // 不会把 App 自己的 MainActivity 带到前台。CLEAR_TASK 保证
                // 面板退到后台后再次点悬浮球时拿到的是当前页，而不是旧面板。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(PanelActivity.EXTRA_PAGE_TEXT, capture?.pageText.orEmpty())
                putExtra(
                    PanelActivity.EXTRA_BOOK_TITLE,
                    capture?.bookTitle ?: ReadingState.lastBookTitle
                )
                putExtra(PanelActivity.EXTRA_SCREENSHOT_PATH, screenshotPath)
            })
            // PanelActivity.onStart 会确认面板已打开；等不到就恢复悬浮球。
            mainHandler.removeCallbacks(panelStartGuardRunnable)
            mainHandler.postDelayed(panelStartGuardRunnable, PANEL_START_TIMEOUT_MS)
        } catch (_: Exception) {
            captureInProgress = false
            setBubbleVisible(true)
            toast(getString(R.string.toast_not_weread))
        }
    }

    private fun saveScreenshot(bitmap: Bitmap): String? {
        return try {
            val maxDimension = 1400
            val largest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (largest > maxDimension) {
                val ratio = maxDimension.toFloat() / largest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val dir = cacheDir
            dir.listFiles { file ->
                file.name.startsWith("page_") &&
                    System.currentTimeMillis() - file.lastModified() > SCREENSHOT_TTL_MS
            }?.forEach { it.delete() }
            val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            file.absolutePath
        } catch (_: Exception) {
            try {
                bitmap.recycle()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun snapToEdge() {
        val view = bubbleView ?: return
        val layoutParams = params ?: return
        val bounds = windowBounds()
        val margin = dp(8)
        val left = margin
        val right = bounds.width() - view.width - margin
        layoutParams.x = if (layoutParams.x + view.width / 2 < bounds.width() / 2) left else right
        layoutParams.y = layoutParams.y.coerceIn(margin, bounds.height() - view.height - margin)
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
        }
        prefs.edit().putInt(KEY_X, layoutParams.x).putInt(KEY_Y, layoutParams.y).apply()
    }

    private fun windowBounds(): Rect {
        return try {
            windowManager.currentWindowMetrics.bounds
        } catch (_: Exception) {
            Rect(
                0,
                0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
        }
    }

    private fun setBubbleVisible(visible: Boolean) {
        val view = bubbleView ?: return
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) {
            view.visibility = target
        }
    }

    /**
     * 面板可见性用 onStart/onStop 同步。关闭面板后稍等再恢复悬浮球，
     * 避免 Activity 重建/切换时悬浮球闪一下。
     */
    private fun onPanelVisibilityChanged(open: Boolean) {
        mainHandler.removeCallbacks(panelClosedRunnable)
        if (open) {
            captureInProgress = false
            mainHandler.removeCallbacks(panelStartGuardRunnable)
            setBubbleVisible(false)
        } else {
            mainHandler.postDelayed(panelClosedRunnable, PANEL_RESTORE_DELAY_MS)
        }
        updateNotificationIfNeeded()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ReadAskBubble"
        private const val CHANNEL_ID = "readask_bubble"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.booxai.helper.STOP_BUBBLE"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val DRAG_UPDATE_INTERVAL_MS = 40L
        private const val SCREENSHOT_DELAY_MS = 150L
        private const val PAGE_SETTLE_MS = 800L
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
        private const val SCREENSHOT_TTL_MS = 60 * 60 * 1000L
        private const val BUBBLE_SIZE_DP = 56
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val PANEL_RESTORE_DELAY_MS = 250L
        private const val PANEL_START_TIMEOUT_MS = 5_000L

        @Volatile
        private var instance: FloatingBubbleService? = null

        // 用计数而不是布尔值：Activity 切换时旧面板的 onStop/onDestroy
        // 可能晚于新面板的 onStart，布尔值会被旧实例错误地置回 false。
        @Volatile
        private var panelVisibleCount: Int = 0

        val panelOpen: Boolean
            get() = panelVisibleCount > 0

        fun onPanelStarted() {
            panelVisibleCount += 1
            instance?.onPanelVisibilityChanged(true)
        }

        fun onPanelStopped() {
            panelVisibleCount = (panelVisibleCount - 1).coerceAtLeast(0)
            instance?.onPanelVisibilityChanged(panelOpen)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingBubbleService::class.java)
            )
        }
    }
}
