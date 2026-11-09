package com.booxai.helper.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormat {
    fun dateTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))

    fun time(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms))

    fun full(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

    fun duration(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        return String.format(Locale.CHINA, "%.1fs", ms / 1000.0)
    }
}
