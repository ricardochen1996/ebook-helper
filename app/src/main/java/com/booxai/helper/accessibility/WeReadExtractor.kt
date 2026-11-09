package com.booxai.helper.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 微信读书墨水屏版的取词器。
 *
 * 实测（v2.1.2）：当前页正文在 book_page 下的 FrameLayout 的 contentDescription 中；
 * 目录面板顶部的 TextView 是书名。阅读页本身不显示书名。
 */
object WeReadExtractor {

    const val PKG = "com.tencent.weread.eink"
    private const val BOOK_PAGE_ID = "$PKG:id/book_page"
    private const val MAX_PAGE_CHARS = 4000

    private val TITLE_BLOCKLIST = setOf(
        "返回", "返回原进度", "书签", "添加书签", "目录", "笔记", "进度", "字体", "听书",
        "搜索全书内容", "去底部", "书评和想法", "涂鸦模式", "分享", "下载", "书架",
        "下拉添加书签", "已添加书签", "已取消书签", "书签已添加",
    )

    fun extractPageTextFromWindows(windows: List<AccessibilityWindowInfo>): String? {
        var best: String? = null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != PKG) continue
            val candidate = extractPageText(root) ?: continue
            if (best == null || candidate.length > best.length) {
                best = candidate
            }
        }
        return best
    }

    fun extractPageText(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val pageNode = findNodeById(root, BOOK_PAGE_ID) ?: root
        val contentDescriptions = mutableListOf<String>()
        val texts = mutableListOf<String>()
        collect(pageNode, contentDescriptions, texts, 0)

        val fromDesc = contentDescriptions.maxByOrNull { it.length }
        val raw = when {
            !fromDesc.isNullOrBlank() && fromDesc.length >= 20 -> fromDesc
            texts.isNotEmpty() -> texts.joinToString("\n")
            else -> null
        } ?: return null
        return normalizePage(raw)
    }

    /**
     * 仅在目录面板打开时返回书名：取顶部区域中第一个像书名的 TextView。
     * 先确认窗口里确实有一批章节/页码节点，避免把“下拉添加书签”这类临时提示当书名。
     */
    fun extractBookTitle(windows: List<AccessibilityWindowInfo>): String? {
        var best: Pair<Int, String>? = null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != PKG) continue
            if (!looksLikeToc(root)) continue
            val candidates = mutableListOf<Pair<Int, String>>()
            collectTitleCandidates(root, candidates, 0)
            for (candidate in candidates) {
                val currentBest = best
                if (currentBest == null || candidate.first < currentBest.first) {
                    best = candidate
                }
            }
        }
        return best?.second
    }

    fun isPlausibleTitle(title: String?): Boolean {
        val text = title?.trim().orEmpty()
        if (text.length !in 2..120) return false
        if (text in TITLE_BLOCKLIST) return false
        if (text.matches(Regex("^\\d+\\s*$"))) return false
        if (text.matches(Regex("^\\d+\\s*[/／]\\s*\\d+$"))) return false
        if (text.matches(Regex("^(\\d+|[一二三四五六七八九十百]+)[\\s、.．].*"))) return false
        if (text.startsWith("第") && text.length in 3..40) return false
        return true
    }

    private fun looksLikeToc(root: AccessibilityNodeInfo): Boolean {
        var pageNumbers = 0
        var chapters = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || pageNumbers >= 3 || chapters >= 3) return
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.matches(Regex("^\\d{1,5}$"))) pageNumbers++
            if (text.matches(Regex("^\\d+\\s+\\S.{2,}$")) ||
                text.matches(Regex("^第[一二三四五六七八九十百零〇\\d]+[章节卷部].{0,60}$"))
            ) {
                chapters++
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        return pageNumbers >= 3 || chapters >= 3
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        contentDescriptions: MutableList<String>,
        texts: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 40) return
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) contentDescriptions.add(desc)
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) texts.add(text)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, contentDescriptions, texts, depth + 1)
        }
    }

    private fun collectTitleCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<Int, String>>,
        depth: Int,
    ) {
        if (depth > 40) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.length in 2..120 && node.className?.toString() == "android.widget.TextView") {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val top = bounds.top
            if (top in 0..230 && isPlausibleTitle(text)) {
                out.add(top to text)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTitleCandidates(child, out, depth + 1)
        }
    }

    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == id) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeById(child, id)
            if (found != null) return found
        }
        return null
    }

    private fun normalizePage(raw: String): String? {
        var text = raw
            .replace('\u0000', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_PAGE_CHARS) {
            text = text.substring(0, MAX_PAGE_CHARS)
        }
        return text
    }
}
