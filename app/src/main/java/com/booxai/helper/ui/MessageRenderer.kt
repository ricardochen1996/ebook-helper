package com.booxai.helper.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.booxai.helper.R
import com.booxai.helper.data.ChatRepository
import com.booxai.helper.data.ScreenshotStore
import com.booxai.helper.util.TimeFormat
import java.io.File

object MessageRenderer {

    fun addMessage(
        container: LinearLayout,
        role: String,
        content: String,
        time: Long? = null,
        interrupted: Boolean = false,
        question: String? = null,
        pageText: String? = null,
        screenshotPath: String? = null,
    ): TextView {
        val context = container.context
        val isUser = role == ChatRepository.ROLE_USER
        val maxBubbleWidth = maxBubbleWidth(context)
        // 用户提问靠右、AI 回答靠左，用气泡底色再次区分。
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = ContextCompat.getDrawable(
                context,
                if (isUser) R.drawable.bg_page else R.drawable.bg_card
            )
        }
        val label = TextView(context).apply {
            text = buildString {
                append(if (isUser) "你" else "AI")
                if (time != null) {
                    append(" · ")
                    append(TimeFormat.time(time))
                }
            }
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            textSize = 12f
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        wrapper.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
            }
        )
        // AI 回答前带上对应的问题，回看记录时不用往上翻也知道在答什么。
        if (!isUser && !question.isNullOrBlank()) {
            val questionView = TextView(context).apply {
                text = context.getString(R.string.message_question, question)
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 13f
                maxLines = 2
                maxWidth = maxBubbleWidth
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.25f)
            }
            wrapper.addView(
                questionView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 2) }
            )
        }
        val body = TextView(context).apply {
            text = if (interrupted) "$content\n${context.getString(R.string.panel_interrupted)}" else content
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            textSize = 16f
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
            maxWidth = maxBubbleWidth
        }
        wrapper.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 3) }
        )
        if (isUser) {
            addPageContext(wrapper, context, pageText, screenshotPath, maxBubbleWidth)
        }
        container.addView(
            wrapper,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = dp(context, 8)
            }
        )
        return body
    }

    /**
     * 回看记录时展示提问当时的书页文本和截图入口：
     * 文本默认折叠 3 行，点“展开”查看全文；截图点按钮后全屏查看。
     */
    private fun addPageContext(
        wrapper: LinearLayout,
        context: Context,
        pageText: String?,
        screenshotPath: String?,
        bubbleMaxWidth: Int,
    ) {
        if (!pageText.isNullOrBlank()) {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(context).apply {
                text = context.getString(R.string.message_page_label, pageText.length)
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 13f
            }
            val toggle = TextView(context).apply {
                text = context.getString(R.string.panel_expand)
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 13f
                setPadding(dp(context, 8), 0, 0, 0)
                isClickable = true
            }
            header.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            header.addView(
                toggle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val body = TextView(context).apply {
                text = pageText
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                maxLines = PAGE_PREVIEW_LINES
                maxWidth = bubbleMaxWidth
                ellipsize = TextUtils.TruncateAt.END
            }
            var expanded = false
            val togglePage = View.OnClickListener {
                expanded = !expanded
                body.maxLines = if (expanded) Int.MAX_VALUE else PAGE_PREVIEW_LINES
                body.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
                toggle.text = context.getString(
                    if (expanded) R.string.panel_collapse else R.string.panel_expand
                )
            }
            label.setOnClickListener(togglePage)
            toggle.setOnClickListener(togglePage)
            wrapper.addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 6) }
            )
            wrapper.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 2) }
            )
        }

        if (ScreenshotStore.exists(screenshotPath)) {
            val file = File(screenshotPath!!)
            val thumbnail = decodeSampled(file, dp(context, THUMBNAIL_WIDTH_DP))
            if (thumbnail != null) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val image = ImageView(context).apply {
                    setImageBitmap(thumbnail)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = context.getString(R.string.message_view_screenshot)
                    background = ContextCompat.getDrawable(context, R.drawable.bg_button)
                    setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
                    setOnClickListener { showScreenshot(context, file) }
                }
                row.addView(
                    image,
                    LinearLayout.LayoutParams(
                        dp(context, THUMBNAIL_WIDTH_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                val hint = TextView(context).apply {
                    text = context.getString(R.string.message_screenshot_hint)
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                    textSize = 13f
                    setLineSpacing(0f, 1.25f)
                    setPadding(dp(context, 10), 0, 0, 0)
                    setOnClickListener { showScreenshot(context, file) }
                }
                row.addView(
                    hint,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                wrapper.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 6) }
                )
            }
        }
    }

    private fun showScreenshot(context: Context, file: File) {
        val metrics = context.resources.displayMetrics
        val bitmap = decodeSampled(file, maxOf(metrics.widthPixels, metrics.heightPixels))
            ?: return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dialog.dismiss() }
        }
        val container = FrameLayout(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.paper))
        }
        container.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setContentView(container)
        dialog.setOnDismissListener {
            try {
                bitmap.recycle()
            } catch (_: Exception) {
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
    }

    private fun decodeSampled(file: File, reqWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun addHint(container: LinearLayout, text: String) {
        val context = container.context
        val view = TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        container.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun maxBubbleWidth(context: Context): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return (screenWidth * MAX_BUBBLE_WIDTH_RATIO).toInt()
            .coerceAtLeast(dp(context, 220))
    }

    private const val PAGE_PREVIEW_LINES = 3
    private const val THUMBNAIL_WIDTH_DP = 120
    private const val MAX_BUBBLE_WIDTH_RATIO = 0.72f
}
