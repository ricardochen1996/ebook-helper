package com.booxai.helper.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.booxai.helper.App
import com.booxai.helper.R
import com.booxai.helper.accessibility.CaptureResult
import com.booxai.helper.accessibility.ReadingState
import com.booxai.helper.accessibility.WeReadAccessibilityService
import com.booxai.helper.ui.MainActivity
import com.booxai.helper.ui.PanelActivity

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private val prefs by lazy { getSharedPreferences("bubble", Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var longPressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_no_overlay))
            stopSelf()
            return
        }
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        params = null
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
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun showBubble() {
        if (bubbleView != null) return
        val size = dp(56)
        val bounds = windowBounds()
        val defaultX = (bounds.width() - size - dp(10)).coerceAtLeast(dp(10))
        val defaultY = (bounds.height() / 2).coerceAtLeast(dp(80))

        val view = TextView(this).apply {
            text = "AI"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@FloatingBubbleService, R.color.ink))
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
        } catch (_: Exception) {
            toast(getString(R.string.toast_no_overlay))
            stopSelf()
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
        if (panelOpen) return
        val service = WeReadAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.toast_no_a11y))
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
        if (capture == null || capture.pageText.isBlank()) {
            toast(getString(R.string.toast_not_weread))
            return
        }
        setBubbleVisible(false)
        startActivity(Intent(this, PanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(PanelActivity.EXTRA_PAGE_TEXT, capture.pageText)
            putExtra(PanelActivity.EXTRA_BOOK_TITLE, capture.bookTitle)
        })
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
        bubbleView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CHANNEL_ID = "readask_bubble"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.booxai.helper.STOP_BUBBLE"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val DRAG_UPDATE_INTERVAL_MS = 40L

        @Volatile
        private var instance: FloatingBubbleService? = null

        @Volatile
        var panelOpen: Boolean = false
            private set

        fun setPanelOpen(open: Boolean) {
            panelOpen = open
            instance?.setBubbleVisible(!open)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingBubbleService::class.java)
            )
        }
    }
}
