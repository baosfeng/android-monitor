package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.CategoryCatalog
import java.security.MessageDigest

// 从 autobook/AutoBookParser.kt 拆出：分类映射、落库幂等键（sourceId / dedupKeys）、时间归一与 md5。
// 幂等键是「同一笔不重复记账」的根据，改前先看 AutoBookParserTest 与 AutoBookDedupTest。

/** 分类映射与幂等键构建。 */
internal object AutoBookIdentity {

    fun mapCategory(parser: AutoBookParser, platformCategory: String?, merchant: String, type: Int): String {
        val isIncome = type == AutoBookDraft.TYPE_INCOME
        if (!platformCategory.isNullOrBlank()) {
            val platformMap = if (isIncome) AutoBookCategoryMaps.INCOME_PLATFORM_CATEGORY_MAP else AutoBookCategoryMaps.PLATFORM_CATEGORY_MAP
            for ((target, keywords) in platformMap) {
                if (keywords.any { platformCategory.contains(it) }) return categoryOrFallback(target, isIncome)
            }
        }
        if (merchant.isNotBlank()) {
            val lower = merchant.lowercase()
            val merchantMap = if (isIncome) AutoBookCategoryMaps.INCOME_MERCHANT_CATEGORY_KEYWORDS else AutoBookCategoryMaps.MERCHANT_CATEGORY_KEYWORDS
            for ((target, keywords) in merchantMap) {
                if (keywords.any { lower.contains(it) }) return categoryOrFallback(target, isIncome)
            }
        }
        return fallbackCategory(isIncome)
    }

    /** 映射目标只允许落在分类表内（一级 + 二级），否则回退兜底一级 */
    private fun categoryOrFallback(target: String, isIncome: Boolean): String =
        if (target in AutoBookCategoryMaps.CATEGORY_NAMES) target else fallbackCategory(isIncome)

    private fun fallbackCategory(isIncome: Boolean): String =
        if (isIncome) AutoBookPatterns.INCOME_FALLBACK else CategoryCatalog.FALLBACK

    /**
     * 生成**落库幂等键**（同一笔只写一条记录）。
     *
     * 优先级：订单号 > 交易时间 > 「渠道 + 方向 + 金额 + 商户 + 5 分钟桶」。
     *
     * ⚠️ 最后一档是**故意保留时间桶**的：落库这一侧宁可重复也不要漏账 ——
     * 若拿「渠道 + 方向 + 金额」当唯一键，用户连着买两笔 3 元会被合并成一笔，那是真丢账。
     * 「同一笔反复弹窗」这个痛点由 [buildDedupKeys] + [AutoBookDedup] 单独解决，不靠这个键。
     */
    fun buildSourceId(parser: AutoBookParser, 
        channel: String,
        type: Int,
        amount: Double,
        merchant: String,
        orderId: String,
        timeText: String,
        nowMs: Long
    ): String {
        val seed = when {
            orderId.isNotEmpty() -> "order:$channel:$orderId"
            timeText.isNotEmpty() -> "time:$channel:$type:$amount:$merchant:$timeText"
            else -> {
                val window = nowMs / AutoBookParser.FALLBACK_WINDOW_MS
                "win:$channel:$type:$amount:$merchant:$window"
            }
        }
        return md5(parser, seed)
    }

    /**
     * 生成**弹窗去重指纹**（多道，任一命中即视为「这笔刚处理过」）。
     *
     * 为什么是多道而不是一个键：页面在渲染中间态下字段会抖 —— 真机实测同一笔在支付宝首页
     * 两次识别出的商户名完全不同（一次「松开刷新」，一次「上海 搜索框 搜索服务或小程序 搜索 更多操作」）。
     * 指纹里只要掺了商户名这种易抖字段，同一笔就会算出两个键，去重失效、浮窗连弹两次。
     *
     * 因此：
     * - **精确指纹**（订单号 / 交易时间）安静期长 —— 它们来自账单自身，跨时间稳定；
     * - **兜底指纹**（渠道 + 方向 + 金额，**刻意不含商户名**）标为 `precise = false` ——
     *   它分不清「同一笔」和「另一笔同金额」，所以只做「刚弹过」的短暂防抖，
     *   **不参与**「已忽略 / 已记账」的长期记忆（否则后一笔同金额的真实消费会被静默吞掉，真机踩过）。
     *
     * @return 至少一条指纹
     */
    fun buildDedupKeys(parser: AutoBookParser, 
        channel: String,
        type: Int,
        amount: Double,
        orderId: String,
        timeText: String
    ): List<DedupKey> {
        val keys = mutableListOf<DedupKey>()
        if (orderId.isNotEmpty()) {
            keys += DedupKey("order:$channel:$orderId", AutoBookParser.PRECISE_KEY_TTL_MS)
        }
        if (timeText.isNotEmpty()) {
            keys += DedupKey("time:$channel:$type:$amount:$timeText", AutoBookParser.PRECISE_KEY_TTL_MS)
        }
        keys += DedupKey("sum:$channel:$type:$amount", AutoBookParser.FUZZY_KEY_TTL_MS, precise = false)
        return keys
    }

    /**
     * 页面交易时间 → `yyyy-MM-dd HH:mm:ss`（**精确到秒**）；解析不出来返回 null。
     *
     * 页面给两种格式（真机实测）：支付宝 `2026-10-16 18:32:21`、微信 `2026年10月16日 18:32:42`。
     *
     * ⚠️ 落库的 [Transaction.date] / [Transaction.dateTime] **必须优先用页面上的交易时间**：
     * 用「点记一笔的时刻」会让同一笔在不同时间被识别时算出不同的落库键（真机就出现过
     * 同一笔 18:32 的交易被记成 20:51、21:00 两条），而且对账时日期是错的。
     */
    fun normalizeTradeTime(parser: AutoBookParser, timeText: String): String? {
        if (timeText.isBlank()) return null
        for (pattern in AutoBookPatterns.TRADE_TIME_PATTERNS) {
            val m = pattern.find(timeText) ?: continue
            // 秒可能缺失（部分模板只给到分），缺失时补 00 —— 精度按页面能给到的最高为准
            return "%04d-%02d-%02d %02d:%02d:%02d".format(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
                m.groupValues[4].toInt(),
                m.groupValues[5].toInt(),
                m.groupValues[6].toIntOrNull() ?: 0
            )
        }
        return null
    }

    /** MD5 十六进制摘要（与项目其它幂等键实现同一种写法） */
    fun md5(parser: AutoBookParser, input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) builder.append('0')
            builder.append(Integer.toHexString(value))
        }
        return builder.toString()
    }
}
