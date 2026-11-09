package com.booxai.helper.accessibility

object ReadingState {
    @Volatile
    var lastPageText: String? = null

    @Volatile
    var lastBookTitle: String? = null

    @Volatile
    var lastCaptureAt: Long = 0L

    /** 最近一次微信读书页面事件时间，用于避开翻页/刷新过程中的截图。 */
    @Volatile
    var lastPageEventAt: Long = 0L

    @Volatile
    var serviceConnected: Boolean = false
}

data class CaptureResult(
    val pageText: String,
    val bookTitle: String?,
    val capturedAt: Long,
)
