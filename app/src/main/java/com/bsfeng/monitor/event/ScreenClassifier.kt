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

        val screenType = classifyByPackage(packageName, className, title, texts)
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
        title: String?,
        texts: List<String>
    ): String {
        // ===== 微信专项识别 =====
        if (pkg == "com.tencent.mm") {
            return when {
                // 小程序 (Mini Program)
                cls?.contains("AppBrandUI") == true ||
                    cls?.contains("MiniProgram") == true -> "mini_program"

                // 支付相关
                cls?.contains("Wallet") == true ||
                    cls?.contains("PayUI") == true ||
                    title?.contains("支付") == true ||
                    title?.contains("钱包") == true -> "payment"

                // 公众号文章
                cls?.contains("BizTimeLineUI") == true ||
                    cls?.contains("BizChattingUI") == true ||
                    title?.contains("公众号") == true -> "official_account"

                // 朋友圈详情（评论）
                cls?.contains("SnsComment") == true ||
                    cls?.contains("SnsTimeLineUI") == true -> "moments_comment"

                // 聊天界面
                cls?.contains("ChattingUI") == true -> "chat"

                // 聊天列表
                cls?.contains("LauncherUI") == true -> "chat_list"

                // 视频号
                cls?.contains("Finder") == true ||
                    cls?.contains("VideoCollection") == true -> "live_stream"

                // 通讯录/联系人
                cls?.contains("AddressUI") == true ||
                    cls?.contains("Contact") == true -> "contacts"

                else -> "unknown"
            }
        }

        // ===== 抖音/快手 =====
        if (pkg == "com.ss.android.ugc.aweme" || pkg == "com.kuaishou.nebula") {
            return when {
                cls?.contains("Live") == true || cls?.contains("Gift") == true -> "live_stream"
                cls?.contains("VideoRecord") == true ||
                    cls?.contains("Camera") == true -> "recording"
                cls?.contains("Detail") == true ||
                    cls?.contains("Feed") == true -> "feed"
                cls?.contains("Search") == true -> "search"
                else -> "video"
            }
        }

        // ===== 淘宝/京东/拼多多 =====
        if (pkg.startsWith("com.taobao") || pkg.startsWith("com.jd") ||
            pkg.startsWith("com.xunmeng") || pkg == "com.pinduoduo") {
            return when {
                cls?.contains("Detail") == true ||
                    cls?.contains("ItemActivity") == true -> "product_detail"
                cls?.contains("Order") == true ||
                    cls?.contains("Confirm") == true ||
                    title?.contains("确认订单") == true ||
                    title?.contains("提交") == true -> "checkout"
                cls?.contains("Cart") == true ||
                    title?.contains("购物车") == true -> "cart"
                cls?.contains("Search") == true -> "search"
                cls?.contains("Home") == true -> "home"
                else -> "shopping"
            }
        }

        // ===== B站/YouTube =====
        if (pkg == "tv.danmaku.bili" || pkg == "com.google.android.youtube") {
            return when {
                cls?.contains("Live") == true -> "live_stream"
                cls?.contains("Player") == true ||
                    cls?.contains("Playback") == true -> "video"
                cls?.contains("Search") == true -> "search"
                cls?.contains("Detail") == true ||
                    cls?.contains("VideoDetail") == true -> "video"
                else -> "feed"
            }
        }

        // ===== 美团/饿了么（外卖） =====
        if (pkg == "com.sankuai.meituan" || pkg == "me.ele" || pkg == "com.taobao.taobao") {
            return when {
                cls?.contains("Order") == true ||
                    title?.contains("订单") == true -> "checkout"
                cls?.contains("Search") == true ||
                    title?.contains("搜索") == true -> "search"
                cls?.contains("Cart") == true ||
                    title?.contains("购物车") == true -> "cart"
                cls?.contains("Detail") == true ||
                    title?.contains("详情") == true -> "product_detail"
                title?.contains("配送") == true ||
                    title?.contains("送达") == true -> "delivery"
                else -> "shopping"
            }
        }

        // ===== 滴滴出行（打车） =====
        if (pkg == "com.didi.sdk" || pkg == "com.caocaokeji.client" || pkg == "com.taobao.trip") {
            return when {
                cls?.contains("Wait") == true ||
                    title?.contains("等待") == true -> "waiting"
                cls?.contains("Order") == true ||
                    title?.contains("行程") == true -> "checkout"
                cls?.contains("Search") == true -> "search"
                title?.contains("目的地") == true -> "navigation"
                else -> "navigation"
            }
        }

        // ===== 通用规则 =====
        return when {
            cls?.contains("video", ignoreCase = true) == true -> "video"
            cls?.contains("live", ignoreCase = true) == true -> "live_stream"
            cls?.contains("article", ignoreCase = true) == true -> "article"
            cls?.contains("search", ignoreCase = true) == true -> "search"
            cls?.contains("settings", ignoreCase = true) == true -> "settings"
            cls?.contains("payment", ignoreCase = true) == true ||
                cls?.contains("checkout", ignoreCase = true) == true -> "checkout"
            cls?.contains("detail", ignoreCase = true) == true &&
                (cls?.contains("product", ignoreCase = true) == true ||
                    cls?.contains("goods", ignoreCase = true) == true) -> "product_detail"
            title?.contains("设置") == true -> "settings"
            title?.contains("搜索") == true -> "search"
            title?.contains("支付") == true ||
                title?.contains("付款") == true -> "payment"
            title?.contains("购物车") == true -> "cart"
            title?.contains("确认订单") == true ||
                title?.contains("提交订单") == true -> "checkout"
            else -> "unknown"
        }
    }

    fun cleanCache() { cache.clear() }
}
