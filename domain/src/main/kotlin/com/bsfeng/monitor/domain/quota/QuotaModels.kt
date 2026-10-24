package com.bsfeng.monitor.domain.quota

import java.math.BigDecimal

/**
 * 额度监控的领域模型 —— 纯 Kotlin，不依赖 Android。
 *
 * 口径：
 * - 所有时间戳是 **epoch 秒（UTC）**，展示时才转本地时区；区间右端不含。
 * - 金额一律 [BigDecimal]（DeepSeek 返回字符串金额，用 Double 累加会对不上账）。
 * - 百分比一律存「剩余」0..100：opencode-go 返回的是「已用」，由 provider 负责换算。
 */

/** 一条额度项的计量方式。 */
enum class QuotaKind {
    /** 金额（如 Ollama 内含额度、DeepSeek 余额）。 */
    MONEY,

    /** 百分比限额（如 Ollama 旧版 session / weekly）。 */
    PERCENT,

    /** 次数（按次数计费的套餐）。 */
    COUNT,
}

/** 额度单位。 */
enum class QuotaUnit(val symbol: String) {
    USD("$"),
    CNY("¥"),
    PERCENT("%"),
    COUNT("次"),
}

/** 计费周期；[endEpochSecond] 不含。 */
data class QuotaPeriod(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
) {
    /** 当前时间在周期内的进度 0..1；周期非法时返回 0。 */
    fun progress(nowEpochSecond: Long): Float {
        val span = endEpochSecond - startEpochSecond
        if (span <= 0L) return 0f
        return ((nowEpochSecond - startEpochSecond).toFloat() / span).coerceIn(0f, 1f)
    }

    /** 距周期结束的剩余天数（不足一天按 1 计，已结束返回 0）。 */
    fun remainingDays(nowEpochSecond: Long): Int {
        val left = endEpochSecond - nowEpochSecond
        if (left <= 0L) return 0
        return ((left + 86_399L) / 86_400L).toInt()
    }
}

/**
 * 一条额度项 —— 余额、内含额度、百分比限额、次数配额都归一到这里。
 *
 * @param id 稳定标识（凭据键 / 预警状态键都用它，发布后不改）
 * @param primary 主项：额度页 hero 展示哪一条
 * @param total 总额；账户余额型为 null（没有「总量」概念）
 */
data class QuotaItem(
    val id: String,
    val title: String,
    val kind: QuotaKind,
    val remaining: BigDecimal,
    val total: BigDecimal? = null,
    val unit: QuotaUnit = QuotaUnit.USD,
    val period: QuotaPeriod? = null,
    val resetsAtEpochSecond: Long? = null,
    val primary: Boolean = false,
    /** 进度条下方的补充明细，如 open-design 的「743 / 50,000 credits」。 */
    val detailText: String? = null,
    /** 标题右侧的档位小标，如 open-design 的「Go」。 */
    val badge: String? = null,
) {
    /**
     * 剩余比例 0..1；没有总额时返回 null。
     *
     * 百分比项直接按 100 归一；金额项按 total 归一，total 为 0 时返回 null（不除零）。
     */
    val remainingFraction: Float?
        get() = when (kind) {
            QuotaKind.PERCENT -> (remaining.toFloat() / 100f).coerceIn(0f, 1f)
            QuotaKind.MONEY, QuotaKind.COUNT -> {
                val t = total
                if (t == null || t.signum() <= 0) null
                else (remaining.toFloat() / t.toFloat()).coerceIn(0f, 1f)
            }
        }

    /** 是否「余额型」：金额且没有总额，只能按绝对金额判断低余额。 */
    val isPlainBalance: Boolean
        get() = kind == QuotaKind.MONEY && total == null
}

/** 用量指标。 */
data class QuotaMetrics(
    val requestCount: Int = 0,
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** 该区间花费；币种与 unit 一致。 */
    val cost: BigDecimal? = null,
)

/** 按模型 / 按 Key 的用量分组。 */
data class QuotaGroup(
    val name: String,
    val metrics: QuotaMetrics,
)

/** 用量分桶（小时 / 天）。 */
data class QuotaBucket(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    /** 该桶是否仍在累计中（当前小时 / 当天）。 */
    val partial: Boolean = false,
    val metrics: QuotaMetrics = QuotaMetrics(),
)

/** 用量时间范围。 */
enum class QuotaRange(val queryValue: String, val title: String, val seconds: Long) {
    LAST_24_HOURS("24h", "24 小时", 86_400L),
    LAST_7_DAYS("7d", "7 天", 7 * 86_400L),
    LAST_30_DAYS("30d", "30 天", 30 * 86_400L),
}

/** 用量分桶粒度。 */
enum class QuotaGranularity { HOUR, DAY }

/** 一次用量查询结果。 */
data class QuotaUsage(
    val range: QuotaRange,
    val granularity: QuotaGranularity,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val totals: QuotaMetrics = QuotaMetrics(),
    val buckets: List<QuotaBucket> = emptyList(),
    val groups: List<QuotaGroup> = emptyList(),
    /** 花费币种（Ollama=USD，DeepSeek=CNY）；没有花费数据时为 null。 */
    val costUnit: QuotaUnit? = null,
)

/** 一次拉取的完整快照；[stale] = 本次刷新失败、沿用上次成功数据。 */
data class QuotaSnapshot(
    val providerId: String,
    val items: List<QuotaItem> = emptyList(),
    val usage: QuotaUsage? = null,
    val fetchedAtEpochSecond: Long = 0L,
    val stale: Boolean = false,
) {
    val primaryItem: QuotaItem?
        get() = items.firstOrNull { it.primary } ?: items.firstOrNull()
}

/** 凭据类型：普通 API Key、平台登录令牌（会过期），或网页 Cookie（open-design 这种内部接口）。 */
enum class QuotaCredentialKind {
    API_KEY,
    PLATFORM_TOKEN,
    SESSION_COOKIE,
}

/** Ollama 的计费模式 —— 由用户在额度页手动选择，选择持久化。 */
enum class OllamaPlanMode(val storageValue: String, val title: String) {
    PERCENT("percent", "按百分比限额"),
    USD("usd", "按金额（USD）"),
    ;

    companion object {
        fun of(storageValue: String?): OllamaPlanMode =
            entries.firstOrNull { it.storageValue == storageValue } ?: PERCENT
    }
}
