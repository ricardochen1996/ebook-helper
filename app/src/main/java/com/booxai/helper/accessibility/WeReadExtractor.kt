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

    // 预编译，避免每次事件/每个节点都重新编译正则。
    private val PAGE_NUMBER = Regex("^\\d{1,5}$")
    private val CHAPTER_NUMBERED = Regex("^\\d+\\s+\\S.{2,}$")
    private val CHAPTER_CN = Regex("^第[一二三四五六七八九十百零〇\\d]+[章节卷部].{0,60}$")
    private val ONLY_DIGITS = Regex("^\\d+\\s*$")
    private val PAGE_RANGE = Regex("^\\d+\\s*[/／]\\s*\\d+$")
    private val CHAPTER_PREFIX = Regex("^(\\d+|[一二三四五六七八九十百]+)[\\s、.．].*")
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val MULTI_SPACE = Regex("[ \\t]+")
    private val MULTI_NEWLINE = Regex("\n{3,}")

    data class PageAndTitle(val pageText: String?, val bookTitle: String?)

    /** 真机联调用：最近一次是否成功通过 resource-id 定位到 book_page。 */
    @Volatile
    var lastFoundByViewId: Boolean = false
        private set

    /**
     * 一次遍历所有窗口，同时取当前页正文和（可选的）书名。
     * 原来的实现先遍历所有窗口取正文，再遍历所有窗口取书名，root 也要取两遍。
     */
    fun extractFromWindows(
        windows: List<AccessibilityWindowInfo>,
        wantTitle: Boolean,
    ): PageAndTitle {
        var bestPage: String? = null
        var bestTitle: String? = null
        var bestTitleTop = Int.MAX_VALUE
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != PKG) continue
            val candidate = extractPageText(root)
            if (candidate != null && (bestPage == null || candidate.length > bestPage.length)) {
                bestPage = candidate
            }
            if (wantTitle) {
                val scan = scanToc(root)
                if (scan.isToc) {
                    for ((top, text) in scan.titles) {
                        if (top < bestTitleTop) {
                            bestTitleTop = top
                            bestTitle = text
                        }
                    }
                }
            }
        }
        return PageAndTitle(bestPage, bestTitle)
    }

    fun extractPageTextFromWindows(windows: List<AccessibilityWindowInfo>): String? =
        extractFromWindows(windows, wantTitle = false).pageText

    fun extractBookTitle(windows: List<AccessibilityWindowInfo>): String? =
        extractFromWindows(windows, wantTitle = true).bookTitle

    fun extractPageText(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val pageNode = findBookPageNode(root) ?: root
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

    private fun findBookPageNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 需要 accessibilityFlags 里的 flagReportViewIds，viewIdResourceName 才有效。
        // 优先走系统按 ID 查找，避免手动全树扫描；找不到再回退。
        val byId = try {
            root.findAccessibilityNodeInfosByViewId(BOOK_PAGE_ID)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        lastFoundByViewId = byId != null
        return byId ?: findNodeById(root, BOOK_PAGE_ID)
    }

    fun isPlausibleTitle(title: String?): Boolean {
        val text = title?.trim().orEmpty()
        if (text.length !in 2..120) return false
        if (text in TITLE_BLOCKLIST) return false
        if (ONLY_DIGITS.matches(text)) return false
        if (PAGE_RANGE.matches(text)) return false
        if (CHAPTER_PREFIX.matches(text)) return false
        if (text.startsWith("第") && text.length in 3..40) return false
        return true
    }

    private data class TocScan(val isToc: Boolean, val titles: List<Pair<Int, String>>)

    /**
     * 一次遍历同时统计目录特征并收集顶部书名候选，
     * 避免原来 looksLikeToc + collectTitleCandidates 的两遍全树扫描。
     */
    private fun scanToc(root: AccessibilityNodeInfo): TocScan {
        var pageNumbers = 0
        var chapters = 0
        val titles = mutableListOf<Pair<Int, String>>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                if (PAGE_NUMBER.matches(text)) pageNumbers++
                if (CHAPTER_NUMBERED.matches(text) || CHAPTER_CN.matches(text)) chapters++
                if (text.length in 2..120 &&
                    node.className?.toString() == "android.widget.TextView" &&
                    isPlausibleTitle(text)
                ) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.top in 0..230) {
                        titles.add(bounds.top to text)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        return TocScan(pageNumbers >= 3 || chapters >= 3, titles)
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
            .replace(ZERO_WIDTH, "")
            .replace(MULTI_SPACE, " ")
            .replace(MULTI_NEWLINE, "\n\n")
            .trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_PAGE_CHARS) {
            text = text.substring(0, MAX_PAGE_CHARS)
        }
        return text
    }
}
