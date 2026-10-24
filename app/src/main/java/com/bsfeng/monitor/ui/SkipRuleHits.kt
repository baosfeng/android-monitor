package com.bsfeng.monitor.ui

// 从 ui/SkipRuleScreen.kt 拆出：「累计命中」数据 —— SkipHits 数据类、loadSkipHits 统计、AD_SKIPPED_ACTION 与 SKIP_TREND_DAYS 常量。负责从事件表算出命中次数与近 7 天走势。

import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.startOfDay

/**
 * s10 摘要「累计命中」的三个数字（原型 08-prototype.html:4594-4599 的第二个 .kpi）。
 *
 * @param total 全量命中次数（kpi-value）
 * @param today 今日命中次数（kpi-delta）
 * @param daily7 近 7 天逐日命中分桶（kpi-spark 迷你走势）
 */
internal data class SkipHits(val total: Int, val today: Int, val daily7: List<Float>) {
    companion object {
        val EMPTY = SkipHits(total = 0, today = 0, daily7 = List(SKIP_TREND_DAYS) { 0f })
    }
}

/**
 * 从 records 表统计跳过命中（与 s03 跳过记录页同一数据源：SCREEN / AD_SKIPPED 事件）。
 *
 * 规则库里只有规则定义、没有命中计数器，真实命中记录只存在于跳过事件表，
 * 因此这里对事件表计数，而不是拿规则条数冒充命中数。调用方需在 IO 线程执行。
 */
internal fun loadSkipHits(store: RecordStore, now: Long): SkipHits {
    val todayStart = startOfDay(now)
    val weekStart = todayStart - (SKIP_TREND_DAYS - 1) * DAY_MS
    val events = store.queryByTypeAndAction(RecordType.SCREEN, AD_SKIPPED_ACTION, 0L, now)
    var today = 0
    val buckets = FloatArray(SKIP_TREND_DAYS)
    events.forEach { record ->
        if (record.timestamp >= todayStart) today++
        val idx = ((record.timestamp - weekStart) / DAY_MS).toInt()
        if (idx in 0 until SKIP_TREND_DAYS) buckets[idx] += 1f
    }
    return SkipHits(total = events.size, today = today, daily7 = buckets.toList())
}

/** 跳过事件的动作名（与 RecordCollector.onSkipAd 写入的 action 一致）。 */
private const val AD_SKIPPED_ACTION = "AD_SKIPPED"

/** 「累计命中」迷你走势的天数（与 s03 跳过趋势同窗口）。 */
private const val SKIP_TREND_DAYS = 7
