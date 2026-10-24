package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：停留期间明细（App 使用时长 / 页面切换次数）。
// 纯聚合计算，只依赖 TripModels 的 UsageSlice / PageTally，可在纯 JVM 单测里直接验证。

// ────────────────────────────── 期间明细 ──────────────────────────────

/**
 * 停留期间 App 使用时长：把使用段裁到 [start, end) 后按 App 聚合，**按降序返回全部**。
 * 抽屉要能看全；摘要/抽屉前若干条由 UI 侧自行截取（见 `visibleApps`）。
 */
internal fun topApps(usage: List<UsageSlice>, start: Long, end: Long): List<UsageSlice> {
    if (usage.isEmpty() || end <= start) return emptyList()
    val acc = LinkedHashMap<String, UsageSlice>()
    var i = TripAggregatorRules.lowerBoundByStart(usage, start)
    while (i > 0 && usage[i - 1].end > start) i--
    while (i < usage.size && usage[i].start <= end) {
        val slice = usage[i]
        if (slice.end > start && slice.start < end) {
            val s = maxOf(slice.start, start)
            val e = minOf(slice.end, end)
            val ms = (e - s).coerceAtLeast(0L)
            if (ms > 0L) {
                val prev = acc[slice.app]
                acc[slice.app] = if (prev == null) {
                    UsageSlice(slice.app, slice.pkg, s, e, ms)
                } else {
                    prev.copy(
                        start = minOf(prev.start, s),
                        end = maxOf(prev.end, e),
                        ms = prev.ms + ms,
                    )
                }
            }
        }
        i++
    }
    return acc.values.sortedByDescending { it.ms }
}

/**
 * 停留期间**每个 App** 的页面切换次数（按次数降序，**不截断**——停留抽屉要完整列表，
 * 摘要行自己只取 Top1）。只数次数，不带任何页面文本（指纹档）。
 */
internal fun tallyPages(pages: List<PageSample>, start: Long, end: Long): List<PageTally> {
    if (pages.isEmpty() || end <= start) return emptyList()
    val counts = LinkedHashMap<String, Int>()
    var i = TripAggregatorRules.lowerBoundByTs(pages, start)
    while (i < pages.size && pages[i].ts < end) {
        val app = pages[i].app
        counts[app] = (counts[app] ?: 0) + 1
        i++
    }
    return counts.entries
        .sortedByDescending { it.value }
        .map { PageTally(it.key, it.value) }
}

internal fun coveragePct(activeMs: Long, windowMs: Long): Int {
    if (windowMs <= 0L) return 0
    return ((activeMs * 100) / windowMs).coerceIn(0L, 100L).toInt()
}
