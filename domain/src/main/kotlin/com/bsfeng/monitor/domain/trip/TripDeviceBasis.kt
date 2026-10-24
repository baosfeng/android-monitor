package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：DEVICE 基准（无定位点时的行程推断）。
// 同一 SSID 连续 ≥10 分钟 → 地点；APP_USAGE 间隔 ≤5 分钟合并成簇；两类候选重叠时合并。
// 以 TripAggregator 扩展函数的形式保留，可直接读取 dayStart / dayEnd / windowMs。

// ──────────────────────────── DEVICE 基准 ────────────────────────────

internal fun TripAggregator.fromDevice(
    usage: List<UsageSlice>,
    wifis: List<WifiSample>,
): DayTimeline {
    if (usage.isEmpty() && wifis.isEmpty()) {
        return DayTimeline(dayStart, listOf(Gap(dayStart, dayEnd)), Basis.DEVICE, coveragePct = 0)
    }

    val candidates = ArrayList<StayRange>()
    // 1) 同一 SSID 连续 ≥10 分钟 = 地点
    for (run in buildSsidRuns(wifis)) {
        if (run.end - run.start >= TripAggregatorRules.STAY_MIN_MS) {
            candidates.add(StayRange(run.start, run.end, run.ssid, Basis.DEVICE))
        }
    }
    // 2) APP_USAGE 间隔 ≤5 分钟合并成簇，簇长 ≥10 分钟 = 停留
    for (cluster in buildUsageClusters(usage)) {
        if (cluster.end - cluster.start >= TripAggregatorRules.STAY_MIN_MS) {
            candidates.add(StayRange(cluster.start, cluster.end, TripAggregatorRules.USAGE_PLACE_LABEL, Basis.DEVICE))
        }
    }
    if (candidates.isEmpty()) {
        return DayTimeline(dayStart, listOf(Gap(dayStart, dayEnd)), Basis.DEVICE, coveragePct = 0)
    }

    val merged = mergeRanges(candidates)
    val segments = ArrayList<DaySegment>()
    var activeMs = 0L
    var cursor = dayStart
    val registry = PlaceRegistry(TripAggregatorRules.USAGE_PLACE_LABEL)
    merged.forEachIndexed { index, range ->
        val start = range.start.coerceIn(dayStart, dayEnd)
        val end = range.end.coerceIn(dayStart, dayEnd)
        if (end <= start) return@forEachIndexed
        if (start > cursor) segments.add(Gap(cursor, start))
        // SSID 同名即同一地点；使用簇没有网络名，统一归到「设备活跃」这一个退化标识
        val place = if (range.label == TripAggregatorRules.USAGE_PLACE_LABEL) {
            registry.device(TripAggregatorRules.DEVICE_ACTIVITY_ID, TripAggregatorRules.USAGE_PLACE_LABEL)
        } else {
            registry.device(TripAggregatorRules.SSID_ID_PREFIX + range.label, range.label)
        }
        segments.add(
            Stay(
                start = start,
                end = end,
                place = place,
                topApps = topApps(usage, start, end),
            ),
        )
        activeMs += end - start
        cursor = end
    }
    if (cursor < dayEnd) segments.add(Gap(cursor, dayEnd))
    return DayTimeline(dayStart, segments, Basis.DEVICE, coveragePct(activeMs, windowMs))
}

/** 同一 SSID 的连续区间；遇到别的 SSID / 断开 / 窗口结束即为区间终点。 */
internal fun TripAggregator.buildSsidRuns(samples: List<WifiSample>): List<SsidRun> {
    val runs = ArrayList<SsidRun>()
    var i = 0
    while (i < samples.size) {
        val head = samples[i]
        val start = head.ts
        var end = dayEnd
        var j = i + 1
        var closed = false
        while (j < samples.size) {
            val next = samples[j]
            val sameSsid = next.ssid == head.ssid
            if (sameSsid && !next.disconnect) {
                j++
                continue
            }
            end = next.ts
            closed = true
            break
        }
        if (!head.disconnect) runs.add(SsidRun(start, end, head.ssid))
        i = if (closed) j else samples.size
    }
    return runs
}

/** 使用簇：按 [TripAggregatorRules.USAGE_MERGE_GAP_MS] 合并相邻使用段。 */
internal fun TripAggregator.buildUsageClusters(usage: List<UsageSlice>): List<UsageCluster> {
    val clusters = ArrayList<UsageCluster>()
    var current: UsageCluster? = null
    for (slice in usage) {
        val c = current
        if (c == null || slice.start - c.end > TripAggregatorRules.USAGE_MERGE_GAP_MS) {
            val fresh = UsageCluster(slice.start, slice.end)
            clusters.add(fresh)
            current = fresh
        } else if (slice.end > c.end) {
            c.end = slice.end
        }
    }
    return clusters
}

/** 候选停留区间按时间合并：只有真正重叠才算同一处（相邻端点相接不合并）；SSID 名字优先于「使用中」。 */
internal fun TripAggregator.mergeRanges(ranges: List<StayRange>): List<StayRange> {
    val sorted = ranges.sortedBy { it.start }
    val merged = ArrayList<StayRange>()
    for (range in sorted) {
        val last = merged.lastOrNull()
        if (last != null && range.start < last.end) {
            if (range.end > last.end) last.end = range.end
            if (last.label == TripAggregatorRules.USAGE_PLACE_LABEL && range.label != TripAggregatorRules.USAGE_PLACE_LABEL) {
                last.label = range.label
            }
        } else {
            merged.add(StayRange(range.start, range.end, range.label, range.source))
        }
    }
    return merged
}
