package com.booxai.helper.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * 在请求执行和响应体读取期间监听协程取消。
 *
 * OkHttp 的 execute()/read 不响应协程取消；这里起一个子协程等取消信号，
 * 一旦父协程被取消就 call.cancel()，让阻塞的 socket 立即返回。
 * 调用方必须在响应体读完（Response.use 返回）之后 cancel() 这个 watcher。
 */
internal suspend fun startCallCancellationWatcher(call: Call): Job =
    CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
