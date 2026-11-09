package com.booxai.helper.accessibility

object ReadingState {
    @Volatile
    var lastPageText: String? = null

    @Volatile
    var lastBookTitle: String? = null

    @Volatile
    var lastCaptureAt: Long = 0L

    @Volatile
    var serviceConnected: Boolean = false
}

data class CaptureResult(
    val pageText: String,
    val bookTitle: String?,
    val capturedAt: Long,
)
