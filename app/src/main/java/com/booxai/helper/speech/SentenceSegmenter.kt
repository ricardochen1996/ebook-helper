package com.booxai.helper.speech

/**
 * 把流式输出切成适合 TTS 的句子。
 * 中文标点为主，兼顾英文；没有标点时按长度在逗号/空格处切。
 */
class SentenceSegmenter {

    private val buffer = StringBuilder()

    fun append(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val boundary = findBoundary()
            if (boundary < 0) break
            val sentence = buffer.substring(0, boundary + 1).trim()
            buffer.delete(0, boundary + 1)
            if (sentence.isNotEmpty()) out.add(sentence)
        }
        if (buffer.length > 220) {
            var cut = -1
            for (i in buffer.length - 1 downTo 60) {
                if (buffer[i] in charArrayOf('，', ',', '、', ' ')) {
                    cut = i
                    break
                }
            }
            if (cut < 0) cut = 160
            val chunk = buffer.substring(0, cut + 1).trim()
            buffer.delete(0, cut + 1)
            if (chunk.isNotEmpty()) out.add(chunk)
        }
        return out
    }

    fun flush(): String? {
        val text = buffer.toString().trim()
        buffer.clear()
        return text.ifEmpty { null }
    }

    fun reset() {
        buffer.clear()
    }

    private fun findBoundary(): Int {
        for (i in buffer.indices) {
            val c = buffer[i]
            val isBoundary = when (c) {
                '。', '！', '？', '；', '\n' -> true
                '!', '?', ';' -> true
                '.' -> {
                    val prev = if (i > 0) buffer[i - 1] else ' '
                    val next = if (i + 1 < buffer.length) buffer[i + 1] else ' '
                    !(prev.isDigit() && next.isDigit()) &&
                        (i + 1 >= buffer.length || next.isWhitespace())
                }
                else -> false
            }
            if (isBoundary) {
                var end = i
                while (end + 1 < buffer.length && buffer[end + 1] in CLOSING_CHARS) {
                    end++
                }
                return end
            }
        }
        return -1
    }

    companion object {
        private val CLOSING_CHARS = charArrayOf(
            '”', '’', '"', '\'', '）', ')', '】', '」', '』', '》'
        )
    }
}

object SpeechSanitizer {

    // 预编译，避免每句朗读都在主线程重新编译正则。
    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    private val MARKDOWN_MARK = Regex("[*_#>`~]+")
    private val BULLET = Regex("(?m)^\\s*[-•]\\s+")
    private val NUMBERED = Regex("(?m)^\\s*\\d+[.、)]\\s+")
    private val HTML_TAG = Regex("<[^>]+>")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MULTI_NEWLINE = Regex("\n{2,}")

    fun sanitize(text: String): String {
        var s = text
        // 代码块、行内代码
        s = s.replace(CODE_BLOCK, " ")
        s = s.replace(INLINE_CODE, "$1")
        // 图片、链接
        s = s.replace(IMAGE, " ")
        s = s.replace(LINK, "$1")
        s = s.replace(URL, "链接")
        // Markdown 标记
        s = s.replace(MARKDOWN_MARK, "")
        s = s.replace(BULLET, "")
        s = s.replace(NUMBERED, "")
        // HTML 标签
        s = s.replace(HTML_TAG, " ")
        // 控制字符
        s = s.replace(CONTROL_CHARS, " ")
        s = s.replace(MULTI_SPACE, " ")
        s = s.replace(MULTI_NEWLINE, "\n")
        return s.trim()
    }
}
