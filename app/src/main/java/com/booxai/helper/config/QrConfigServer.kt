package com.booxai.helper.config

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 局域网二维码配置服务：
 * 电子书显示 http://<lan-ip>:<port>/config?token=... 的二维码，
 * 手机在同一 Wi-Fi 下打开页面填写 API 配置，POST 回电子书。
 *
 * 只监听一个随机端口，使用一次性 token，保存成功或 5 分钟无请求后自动关闭。
 */
class QrConfigServer(
    private val currentProtocol: String,
    private val currentBaseUrl: String,
    private val currentModel: String,
    private val hasExistingKey: Boolean,
    private val onSaved: (Payload) -> Unit,
    private val onError: (String) -> Unit,
    private val onTimeout: () -> Unit,
) {

    data class Payload(
        val protocol: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String?,
    )

    private val token = ByteArray(16)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var lanIp: String? = null
        private set

    @Volatile
    var port: Int = 0
        private set

    val url: String?
        get() = lanIp?.let { "http://$it:$port/config?token=$token" }

    fun start(): Boolean {
        if (running.get()) return true
        val ip = findLanIp()
        if (ip == null) {
            onError("未连接到 Wi-Fi")
            return false
        }
        lanIp = ip
        val socket = try {
            ServerSocket(0)
        } catch (e: Exception) {
            onError("无法启动配置服务：${e.message}")
            return false
        }
        socket.soTimeout = TIMEOUT_MS
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        thread(name = "readask-qr-config", isDaemon = true) {
            acceptLoop(socket)
        }
        return true
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                if (running.get()) onTimeout()
                break
            } catch (_: Exception) {
                break
            }
            try {
                handle(client)
            } catch (_: Exception) {
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        if (query["token"] != token) {
            respond(client, "403 Forbidden", page("链接已失效", "<p class=\"err\">请重新在电子书上打开配置页。</p>"))
            return
        }

        when {
            method == "GET" && (path == "/" || path == "/config") -> {
                respond(client, "200 OK", formHtml(null))
            }

            method == "POST" && path == "/save" -> {
                val body = readBody(input, contentLength)
                val params = parseQuery(body)
                val protocol = params["protocol"].orEmpty()
                val baseUrl = params["base_url"].orEmpty().trim()
                val model = params["model"].orEmpty().trim()
                val apiKey = params["api_key"].orEmpty().trim()
                val error = validate(protocol, baseUrl, model, apiKey)
                if (error != null) {
                    respond(client, "400 Bad Request", formHtml(error))
                    return
                }
                respond(client, "200 OK", successHtml())
                running.set(false)
                onSaved(Payload(protocol, baseUrl, model, apiKey.ifEmpty { null }))
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }
            }

            else -> respond(client, "404 Not Found", page("404", "<p>页面不存在。</p>"))
        }
    }

    private fun validate(protocol: String, baseUrl: String, model: String, apiKey: String): String? {
        if (protocol != "openai" && protocol != "anthropic") return "协议不正确"
        if (baseUrl.isEmpty()) return "请填写 Base URL"
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return "Base URL 需要以 http:// 或 https:// 开头"
        }
        try {
            val uri = URI(baseUrl)
            if (uri.host.isNullOrBlank()) return "Base URL 格式不正确"
        } catch (_: Exception) {
            return "Base URL 格式不正确"
        }
        if (model.isEmpty()) return "请填写模型"
        if (apiKey.isEmpty() && !hasExistingKey) return "请填写 API Key"
        if (baseUrl.length > 500 || model.length > 200 || apiKey.length > 500) {
            return "内容过长"
        }
        return null
    }

    private fun formHtml(error: String?): String {
        val errorHtml = error?.let { "<p class=\"err\">${esc(it)}</p>" }.orEmpty()
        val keyHint = if (hasExistingKey) {
            "留空保持不变（电子书里已保存 Key）"
        } else {
            "必填"
        }
        val keyRequired = if (hasExistingKey) "" else " required"
        val openaiSelected = if (currentProtocol == "anthropic") "" else " selected"
        val anthropicSelected = if (currentProtocol == "anthropic") " selected" else ""
        val body = """
            <h1>读问 AI · API 配置</h1>
            <p class="hint">在手机上填写后，电子书会自动保存。手机和电子书需要连接同一个 Wi-Fi。</p>
            $errorHtml
            <form method="post" action="/save?token=$token" autocomplete="off">
              <label>协议</label>
              <select name="protocol">
                <option value="openai"$openaiSelected>OpenAI 兼容</option>
                <option value="anthropic"$anthropicSelected>Anthropic</option>
              </select>
              <label>Base URL</label>
              <input name="base_url" type="url" required spellcheck="false"
                     value="${esc(currentBaseUrl)}" placeholder="https://api.deepseek.com">
              <label>模型</label>
              <input name="model" type="text" required spellcheck="false"
                     value="${esc(currentModel)}" placeholder="deepseek-chat">
              <label>API Key</label>
              <input name="api_key" type="password" autocomplete="off" spellcheck="false"
                     placeholder="${esc(keyHint)}"$keyRequired>
              <p class="note">Key 只写入这台电子书，不经过其他服务器。</p>
              <button type="submit">保存到电子书</button>
            </form>
            <p class="note">提交后本页可以关闭，电子书上的二维码页面会自动返回。此页面只在配置期间有效。</p>
        """.trimIndent()
        return page("读问 AI 配置", body)
    }

    private fun successHtml(): String = page(
        "配置已保存",
        """
            <h1>配置已保存</h1>
            <p class="ok">已写入电子书，可以关闭此页面。</p>
            <p class="note">电子书上的配置页会自动返回。</p>
        """.trimIndent()
    )

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>${esc(title)}</title>
          <style>
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;background:#fff;color:#111}
            .wrap{max-width:560px;margin:0 auto;padding:24px 18px 40px}
            h1{font-size:22px;margin:0 0 8px}
            .hint{color:#555;font-size:14px;line-height:1.6;margin:0 0 18px}
            label{display:block;font-size:14px;color:#333;margin:16px 0 6px}
            input,select{width:100%;box-sizing:border-box;font-size:16px;padding:12px;border:1px solid #999;border-radius:8px;background:#fff;color:#111}
            button{width:100%;margin-top:24px;padding:14px;font-size:17px;border:0;border-radius:8px;background:#111;color:#fff}
            .err{background:#fff2f2;border:1px solid #c33;color:#900;padding:10px 12px;border-radius:8px;margin:0 0 14px;font-size:14px}
            .note{color:#666;font-size:13px;line-height:1.6;margin-top:16px}
            .ok{font-size:18px;line-height:1.7;margin-top:18px}
          </style>
        </head>
        <body><div class="wrap">$body</div></body>
        </html>
    """.trimIndent()

    private fun respond(client: Socket, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().use { out ->
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0 || length > 64_000) return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) {
                if (sb.length > 8192) return sb.toString()
                sb.append(b.toChar())
            }
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            raw.split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                key to value
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun findLanIp(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val wifi = interfaces.filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            val ordered = wifi + interfaces.filter { it !in wifi }
            val candidates = mutableListOf<String>()
            for (ni in ordered) {
                if (!ni.isUp || ni.isLoopback) continue
                try {
                    for (addr in Collections.list(ni.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            if (addr.isSiteLocalAddress) return host
                            candidates.add(host)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun esc(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5 * 60_000
    }
}
