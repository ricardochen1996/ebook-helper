package com.booxai.helper.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.booxai.helper.R
import com.booxai.helper.data.ChatRepository
import com.booxai.helper.util.TimeFormat

object MessageRenderer {

    fun addMessage(
        container: LinearLayout,
        role: String,
        content: String,
        time: Long? = null,
        interrupted: Boolean = false,
    ): TextView {
        val context = container.context
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
        }
        val label = TextView(context).apply {
            text = buildString {
                append(if (role == ChatRepository.ROLE_USER) "你" else "AI")
                if (time != null) {
                    append(" · ")
                    append(TimeFormat.time(time))
                }
            }
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            textSize = 12f
        }
        val body = TextView(context).apply {
            text = if (interrupted) "$content\n${context.getString(R.string.panel_interrupted)}" else content
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            textSize = 16f
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        }
        wrapper.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        wrapper.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 3) }
        )
        container.addView(
            wrapper,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return body
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
}
