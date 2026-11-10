package com.booxai.helper.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 验证取消协程能中断阻塞中的响应体读取：
 * 慢服务器发完响应头后卡住 body，取消后 watcher 应主动 call.cancel()。
 */
class CallCancellationTest {

    @Test
    fun cancellingCoroutineInterruptsBlockingBodyRead() = runBlocking {
        val serverSocket = ServerSocket(0)
        val serverThread = thread(isDaemon = true, name = "slow-body-server") {
            try {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = socket.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: 1000000\r\n\r\n").toByteArray()
                    )
                    out.write(ByteArray(1024))
                    out.flush()
                    Thread.sleep(10_000)
                }
            } catch (_: Exception) {
            }
        }
        try {
            val call = OkHttpClient().newCall(
                Request.Builder()
                    .url("http://127.0.0.1:${serverSocket.localPort}/slow")
                    .build()
            )
            val job = launch(Dispatchers.IO) {
                val watcher = startCallCancellationWatcher(call)
                try {
                    call.execute().use { response ->
                        response.body!!.byteStream().use { it.readBytes() }
                    }
                } catch (e: IOException) {
                    // 取消会关闭 socket，读取抛 IOException；确认是取消而不是网络错误
                    currentCoroutineContext().ensureActive()
                    throw e
                } finally {
                    watcher.cancel()
                }
            }
            delay(500)
            val startedAt = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            println("cancel took ${elapsedMs}ms")
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 2_000)
        } finally {
            serverSocket.close()
            serverThread.interrupt()
        }
    }
}
