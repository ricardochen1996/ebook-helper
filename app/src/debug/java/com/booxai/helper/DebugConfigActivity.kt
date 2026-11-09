package com.booxai.helper

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.booxai.helper.data.SecretStore

/**
 * 仅 debug 构建包含：通过 adb 快速写入 API 配置，便于真机联调。
 *
 * adb shell am start -n com.booxai.helper.debug/com.booxai.helper.DebugConfigActivity \
 *   --es base_url http://127.0.0.1:8765/v1 --es model mock \
 *   --es api_key test --es protocol openai
 */
class DebugConfigActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = intent.getStringExtra("base_url")
        val model = intent.getStringExtra("model")
        val apiKey = intent.getStringExtra("api_key")
        val protocol = intent.getStringExtra("protocol")
        val lastBookTitle = intent.getStringExtra("last_book_title")
        if (!baseUrl.isNullOrBlank()) App.settings.baseUrl = baseUrl
        if (!model.isNullOrBlank()) App.settings.model = model
        if (!protocol.isNullOrBlank()) App.settings.protocol = protocol
        if (!apiKey.isNullOrBlank()) App.secrets.put(SecretStore.KEY_LLM, apiKey)
        if (lastBookTitle != null) App.settings.lastBookTitle = lastBookTitle
        val debugQuestion = intent.getStringExtra("debug_question")
        val netHost = intent.getStringExtra("debug_net_host")
        if (intent.getBooleanExtra("debug_capture", false)) {
            val result = com.booxai.helper.accessibility.WeReadAccessibilityService.instance?.capture()
            android.util.Log.i(
                "ReadAskDebug",
                "capture title=${result?.bookTitle} len=${result?.pageText?.length} " +
                    "byId=${com.booxai.helper.accessibility.WeReadExtractor.lastFoundByViewId} " +
                    "preview=${result?.pageText?.take(60)}"
            )
        }
        if (!netHost.isNullOrBlank()) {
            val port = intent.getIntExtra("debug_net_port", 8765)
            Thread {
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(netHost, port), 5000)
                    }
                    android.util.Log.i("ReadAskDebug", "raw socket OK $netHost:$port")
                } catch (e: Exception) {
                    android.util.Log.e("ReadAskDebug", "raw socket FAIL $netHost:$port", e)
                }
            }.start()
        }
        if (!debugQuestion.isNullOrBlank()) {
            startActivity(
                android.content.Intent(this, com.booxai.helper.ui.PanelActivity::class.java).apply {
                    putExtra(
                        com.booxai.helper.ui.PanelActivity.EXTRA_PAGE_TEXT,
                        intent.getStringExtra("debug_page") ?: "（测试页面，仅用于联调。）"
                    )
                    putExtra(
                        com.booxai.helper.ui.PanelActivity.EXTRA_BOOK_TITLE,
                        lastBookTitle?.takeIf { it.isNotBlank() } ?: "测试书籍"
                    )
                    putExtra(com.booxai.helper.ui.PanelActivity.EXTRA_DEBUG_QUESTION, debugQuestion)
                }
            )
        }
        Toast.makeText(this, "debug config saved", Toast.LENGTH_LONG).show()
        finish()
    }
}
