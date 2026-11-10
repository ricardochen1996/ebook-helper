package com.booxai.helper.update

/**
 * 版本号比较：只按数字段判断，忽略 v 前缀和 -debug/-beta 之类的后缀。
 *
 * 1.1 与 1.1.0 视为同一版本（缺失段按 0 处理）；
 * 解析失败返回 false，宁可漏提示也不误报更新。
 */
object VersionComparator {

    fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = parse(remote) ?: return false
        val currentParts = parse(current) ?: return false
        val size = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until size) {
            val remotePart = remoteParts.getOrElse(i) { 0L }
            val currentPart = currentParts.getOrElse(i) { 0L }
            if (remotePart > currentPart) return true
            if (remotePart < currentPart) return false
        }
        return false
    }

    private fun parse(raw: String): List<Long>? {
        var text = raw.trim()
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1)
        }
        // 预发布/构建后缀不参与比较：1.1.0-debug 应等于 1.1.0
        text = text.substringBefore('-').substringBefore('+').trim()
        if (text.isEmpty()) return null
        return text.split('.').map { part ->
            // 只接受纯数字段，避免把 "1.0.x" 之类误判成可比较版本
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
            part.toLongOrNull() ?: return null
        }
    }
}
