package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过页的文案格式化 —— 跳过方式说明（含耗时）、节省时长、近 7 天峰值说明。
// 负责「这条记录怎么读成人话」，不含任何 UI。

import com.bsfeng.monitor.ui.live.PLACEHOLDER
import java.util.Locale

internal fun methodSubtitle(method: SkipMethod, costMs: Long?): String {
    val head = when (method) {
        SkipMethod.COORD -> "坐标点击兜底"
        SkipMethod.A11Y -> "无障碍事件兜底"
        else -> "规则匹配"
    }
    return head + " · " + (costMs?.let { it.toString() + " ms" } ?: PLACEHOLDER)
}

internal fun formatSaved(ms: Long): String {
    val totalSec = ms / 1000
    return (totalSec / 60).toString() + "m" + String.format(Locale.US, "%02d", totalSec % 60) + "s"
}

/**
 * 趋势卡尾注：把「已确认 / 均耗 / 节省」三项回顾性数据拼成一行。
 *
 * 任一项无数据就不出现该项；三项都无数据时返回空串，调用方整行不渲染 ——
 * 不允许出现「均耗 —」这类占位（真机上空格子只占宽、不承载信息）。
 */
internal fun reviewExtras(confirmedCount: Int, avgCost: Long?, savedTotal: Long?): String {
    val parts = buildList {
        if (confirmedCount > 0) add("已确认 " + confirmedCount + " 次")
        if (avgCost != null) add("均耗 " + avgCost + " ms")
        if (savedTotal != null) add("共省 " + formatSaved(savedTotal.coerceAtLeast(0L)))
    }
    return parts.joinToString(" · ")
}

// ───────────────── 命中方式 / 跳过耗时（锚点 s03-breakdown，纯派生） ─────────────────

/** 命中方式三档计数（顺序与原型 s03-breakdown 三行一致，字段名即行序）。 */
internal data class SkipMethodCounts(val rule: Int, val coord: Int, val a11y: Int) {
    val total: Int get() = rule + coord + a11y
}

/**
 * 按 [SkipRecord.method] 计数。
 *
 * [SkipMethod.ALL] 只是筛选 chip 的伪成员（classify 不会产出它），出现即忽略，
 * 否则它会被算成第四个命中方式。
 */
internal fun skipMethodCounts(records: List<SkipRecord>): SkipMethodCounts {
    var rule = 0
    var coord = 0
    var a11y = 0
    records.forEach { record ->
        when (record.method) {
            SkipMethod.RULE -> rule++
            SkipMethod.COORD -> coord++
            SkipMethod.A11Y -> a11y++
            SkipMethod.ALL -> Unit
        }
    }
    return SkipMethodCounts(rule = rule, coord = coord, a11y = a11y)
}

/** 跳过耗时 4 桶的轴标签（原型 s03-breakdown 的 data-chart-labels）。 */
internal val SKIP_COST_BUCKET_LABELS = listOf("<100", "100-200", "200-400", ">400")

/**
 * 耗时 4 桶：100 / 200 归后一桶，400 仍属「200-400」，>400 严格大于。
 * 没有 cost_ms 的记录（采集端可选字段）不参与分桶，也不补 0 到任何一桶。
 */
internal fun skipCostBuckets(records: List<SkipRecord>): List<Int> {
    val buckets = IntArray(SKIP_COST_BUCKET_LABELS.size)
    records.forEach { record ->
        val ms = record.costMs ?: return@forEach
        when {
            ms < 100 -> buckets[0]++
            ms < 200 -> buckets[1]++
            ms <= 400 -> buckets[2]++
            else -> buckets[3]++
        }
    }
    return buckets.toList()
}

/** 跳过耗时的中位与最慢（毫秒）。 */
internal data class SkipCostSummary(val medianMs: Long, val maxMs: Long)

/**
 * 中位 / 最慢；没有任何 cost_ms 样本时返回 null —— 调用方整段不渲染，
 * 与 [reviewExtras]「缺项不出现」同一口径（不允许「中位 — ms」这种占位）。
 *
 * 偶数个样本取中间两数的均值并四舍五入到整数毫秒（耗时只展示整数）。
 */
internal fun skipCostSummary(records: List<SkipRecord>): SkipCostSummary? {
    val costs = records.mapNotNull { it.costMs }.sorted()
    if (costs.isEmpty()) return null
    val mid = costs.size / 2
    val median = if (costs.size % 2 == 1) costs[mid] else (costs[mid - 1] + costs[mid] + 1) / 2
    return SkipCostSummary(medianMs = median, maxMs = costs.last())
}

internal fun peakNote(values: List<Float>): String {
    var idx = 0
    var max = 0f
    values.forEachIndexed { i, v ->
        if (v > max) {
            max = v
            idx = i
        }
    }
    if (max <= 0f) return "近 7 天暂无跳过记录"
    return "峰值 " + (idx + 1) + " 日 · " + max.toInt() + " 次"
}
