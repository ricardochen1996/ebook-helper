package com.booxai.helper.ai

import okio.BufferedSource

data class SseEvent(val event: String, val data: String)

/**
 * 极简 SSE 解析器：按行读取，空行分派事件。
 * 支持 CRLF、注释行、event/data/id/retry 字段与多行 data。
 */
class SseReader(private val source: BufferedSource) {

    fun readEvents(): Sequence<SseEvent> = sequence {
        var event = ""
        val data = StringBuilder()
        while (true) {
            val rawLine = source.readUtf8Line() ?: break
            val line = rawLine.trimEnd('\r')
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty() || event.isNotEmpty()) {
                        yield(SseEvent(event, data.toString()))
                        event = ""
                        data.clear()
                    }
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter("data:").removePrefix(" "))
                }
                line.startsWith("id:") || line.startsWith("retry:") -> Unit
                else -> Unit
            }
        }
        if (data.isNotEmpty() || event.isNotEmpty()) {
            yield(SseEvent(event, data.toString()))
        }
    }
}

object LlmErrors {
    fun http(code: Int, body: String): String {
        val serverMessage = try {
            val json = org.json.JSONObject(body)
            (json.optJSONObject("error")?.opt("message") as? String)
                ?: (json.opt("message") as? String)
        } catch (_: Exception) {
            ""
        }.orEmpty()
        val detail = serverMessage.ifEmpty { body.take(300) }
        return when (code) {
            400 -> "请求参数有误（400）：${detail.ifEmpty { "请检查模型名与参数" }}"
            401 -> "认证失败（401）：请检查 API Key 是否正确、是否属于该服务商"
            403 -> "无权限（403）：Key 可能无权访问该模型，或账号/地区受限"
            404 -> "接口不存在（404）：请检查 Base URL 是否包含正确的 /v1 等前缀，以及模型名"
            429 -> "请求过多或额度不足（429）：请稍后再试或检查账户额度"
            in 500..599 -> "服务端错误（$code）：${detail.ifEmpty { "请稍后重试" }}"
            else -> "HTTP $code：${detail.ifEmpty { "未知错误" }}"
        }
    }

    fun network(error: Throwable): String {
        val msg = error.message.orEmpty()
        return when {
            msg.contains("timeout", ignoreCase = true) -> "请求超时，请检查网络或稍后重试"
            msg.contains("Unable to resolve host", ignoreCase = true) -> "无法解析服务器地址，请检查网络/Base URL"
            msg.contains("Failed to connect", ignoreCase = true) -> "无法连接服务器，请检查网络/代理"
            msg.contains("CLEARTEXT", ignoreCase = true) -> "该地址不允许明文 HTTP，请改用 HTTPS 或允许明文流量"
            else -> "网络错误：${msg.ifEmpty { error.javaClass.simpleName }}"
        }
    }
}
