package com.bsfeng.monitor.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 页面内容提取器 — 从 AccessibilityEvent 和无障碍树中提取有意义的文本。
 *
 * 职责：
 * - 从 event 本身、event.source 节点树、rootInActiveWindow 提取文本
 * - 过滤无意义文本
 * - 选择最佳页面标题
 */
class PageContentExtractor {

    companion object {
        private const val TAG = "PageContentExtractor"

        /** 过滤无意义的占位文本 */
        fun isNonsenseText(text: String): Boolean {
            val lower = text.lowercase()
            return lower in setOf(
                "返回", "取消", "确定", "确认", "关闭", "更多", "分享",
                "退出", "保存", "删除", "编辑", "完成", "下一步",
                "back", "cancel", "ok", "close", "more", "share"
            ) || text.all { it.isWhitespace() || it.isDigit() || it == ':' || it == '/' }
        }

        /** 构建 content_text_N 的 rawExtra 映射 */
        fun buildContentTextsMap(texts: List<String>): Map<String, String> {
            if (texts.isEmpty()) return emptyMap()
            val map = mutableMapOf<String, String>()
            for (i in texts.indices) {
                val key = "content_text_${i + 1}"
                val value = texts[i]
                map[key] = value
                if (i >= 7) break
            }
            return map
        }
    }

    /**
     * 从 AccessibilityEvent 中提取页面内容。
     * 返回 (bestTitle, contentTexts)
     */
    fun extract(
        event: AccessibilityEvent,
        rootProvider: () -> AccessibilityNodeInfo?
    ): Pair<String?, List<String>> {
        val allTexts = linkedSetOf<String>()

        // 1. 从 event 本身获取
        event.text?.joinToString("")?.takeIf { it.isNotBlank() }?.let {
            allTexts.add(it.trim())
        }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            allTexts.add(it.trim())
        }

        // 2. 从 event.source 节点树遍历
        event.source?.let { source ->
            collectContentTexts(source, allTexts, max = 30, depth = 0, maxDepth = 12)
        }

        // 3. 从 rootInActiveWindow 补充
        try {
            val root = rootProvider()
            if (root != null && root != event.source) {
                val rootPkg = root.packageName?.toString()
                if (rootPkg == event.packageName?.toString()) {
                    collectContentTexts(root, allTexts, max = 30, depth = 0, maxDepth = 12)
                }
                root.recycle()
            }
        } catch (_: Exception) {}

        if (allTexts.isEmpty()) return Pair(null, emptyList())

        // 按长度降序排列，过滤无意义短文本
        val meaningful = allTexts
            .map { it.trim() }
            .filter { it.length >= 2 && !isNonsenseText(it) }
            .distinct()
            .sortedByDescending { it.length }

        val bestTitle = meaningful.firstOrNull { it.length >= 6 }
            ?: meaningful.firstOrNull()
        val topTexts = meaningful.take(8)

        if (topTexts.isNotEmpty()) {
            Log.d(TAG, "texts[${topTexts.size}]: ${topTexts.joinToString(" | ") { it.take(40) }}")
        }

        return Pair(bestTitle, topTexts)
    }

    /** 从无障碍节点树中收集有意义的文本 */
    fun collectContentTexts(
        node: AccessibilityNodeInfo,
        texts: MutableSet<String>,
        max: Int,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth || texts.size >= max) return

        node.text?.toString()?.takeIf {
            it.isNotBlank() && it.trim().length >= 2
        }?.let { t ->
            val trimmed = t.trim()
            if (trimmed !in texts) texts.add(trimmed)
        }

        node.contentDescription?.toString()?.takeIf {
            it.isNotBlank() && it.trim().length >= 2
        }?.let { cd ->
            val trimmed = cd.trim()
            if (trimmed !in texts) texts.add(trimmed)
        }

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let {
                    collectContentTexts(it, texts, max, depth + 1, maxDepth)
                }
            } catch (_: Exception) {}
        }
    }
}
