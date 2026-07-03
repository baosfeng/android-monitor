package com.bsfeng.monitor.event

import android.view.accessibility.AccessibilityNodeInfo

data class ScreenContent(
    val screenTitle: String?,
    val screenType: String,
    val confidence: Float
)

object ScreenClassifier {
    private val cache = mutableMapOf<String, Long>()
    private const val CACHE_TTL_MS = 30_000L

    fun classify(
        packageName: String,
        className: String?,
        windowText: List<CharSequence>?,
        rootNode: AccessibilityNodeInfo?
    ): ScreenContent? {
        val title = windowText?.joinToString("")?.takeIf { it.isNotBlank() }
        val cacheKey = "$packageName:$title"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { if (now - it < CACHE_TTL_MS) return null }
        cache[cacheKey] = now

        val texts = mutableListOf<String>()
        rootNode?.let { traverseNode(it, texts, depth = 0, maxDepth = 3) }

        val screenType = classifyByPackage(packageName, className, title)
        return ScreenContent(
            screenTitle = title ?: texts.firstOrNull(),
            screenType = screenType,
            confidence = if (screenType != "unknown") 0.85f else 0.5f
        )
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth || texts.size >= 3) return
        node.text?.toString()?.takeIf { it.isNotBlank() && it.length > 2 }?.let {
            texts.add(it)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                traverseNode(it, texts, depth + 1, maxDepth)
            }
        }
    }

    private fun classifyByPackage(
        pkg: String,
        cls: String?,
        title: String?
    ): String {
        return when {
            pkg == "com.tencent.mm" && cls?.contains("ChattingUI") == true -> "chat"
            pkg == "com.tencent.mm" && cls?.contains("SnsTimeLineUI") == true -> "feed"
            pkg == "com.tencent.mm" && cls?.contains("BizTimeLineUI") == true -> "article"
            pkg == "com.tencent.mm" && cls?.contains("LauncherUI") == true -> "chat_list"
            cls?.contains("video", ignoreCase = true) == true -> "video"
            cls?.contains("article", ignoreCase = true) == true -> "article"
            cls?.contains("search", ignoreCase = true) == true -> "search"
            cls?.contains("settings", ignoreCase = true) == true -> "settings"
            title?.contains("设置") == true -> "settings"
            title?.contains("搜索") == true -> "search"
            else -> "unknown"
        }
    }

    fun cleanCache() { cache.clear() }
}
