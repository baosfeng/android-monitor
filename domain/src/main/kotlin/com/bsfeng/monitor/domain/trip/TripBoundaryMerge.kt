package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：跨天边界的「未记录」归并。
// 聚合只吃当天窗口内的记录，所以「昨天 23:00 在家 + 今天 00:30 在家」中间那半小时必然落成 Gap，
// 哪怕两头相距 2m。这里用窗口外最近的定位点判定边界归属，把首 / 末 Gap 吞进同一地点的停留。
// 只认 LOCATION 点；不同址一律不吞（真实换地点必须留下未记录）。

// ──────────────────────── 边界 Gap 的归并判定 ────────────────────────

/**
 * 边界 Gap 能否并入 [stay]：参照点 [edgePoint] 必须与停留同址，且三段时间都不超过 2 小时。
 *
 * 四个条件缺一不可（任一不满足就保留 Gap）：
 * - 有窗口外的参照点（没有参照就无从判断归属）；
 * - Gap 自身 ≤2h（更长的空档属于「采集断了」，不能当成同址延续）；
 * - 参照点距边界 ≤2h（更早的点说明中间断过，不能拿来担保）；
 * - 参照点与停留地点 ≤80m（同一处；DEVICE 地点没有坐标，直接不吞）。
 */
internal fun canAbsorbBoundaryGap(
    gap: Gap,
    stay: Stay,
    edgePoint: LocationPoint?,
    edgeTs: Long,
): Boolean {
    if (edgePoint == null) return false
    if (gap.durationMs > TripAggregatorRules.BOUNDARY_CONTEXT_MS) return false
    val offset = if (edgeTs >= edgePoint.ts) edgeTs - edgePoint.ts else edgePoint.ts - edgeTs
    if (offset > TripAggregatorRules.BOUNDARY_CONTEXT_MS) return false
    val lat = stay.place.lat ?: return false
    val lng = stay.place.lng ?: return false
    return haversineMeters(lat, lng, edgePoint.lat, edgePoint.lng) <= TripAggregatorRules.STAY_RADIUS_M
}

/**
 * 日首 / 日尾的 Gap 归并：只处理「紧贴窗口边界、且紧邻一段停留」的那一个 Gap。
 * 吞掉后停留扩到边界（首 Stay.start = dayStart / 末 Stay.end = dayEnd），期间 App 使用按新区间重算。
 */
internal fun mergeBoundaryGaps(
    segments: List<DaySegment>,
    usage: List<UsageSlice>,
    dayStart: Long,
    dayEnd: Long,
    before: LocationPoint?,
    after: LocationPoint?,
): List<DaySegment> {
    if (segments.size < 2) return segments
    var list = segments

    val head = list[0]
    val headStay = list[1]
    if (head is Gap && head.start == dayStart && headStay is Stay &&
        canAbsorbBoundaryGap(head, headStay, before, dayStart)
    ) {
        val merged = headStay.copy(start = dayStart, topApps = topApps(usage, dayStart, headStay.end))
        list = buildList {
            add(merged)
            addAll(list.drop(2))
        }
    }

    val tail = list[list.size - 1]
    val tailStay = list[list.size - 2]
    if (tail is Gap && tail.end == dayEnd && tailStay is Stay &&
        canAbsorbBoundaryGap(tail, tailStay, after, dayEnd)
    ) {
        val merged = tailStay.copy(end = dayEnd, topApps = topApps(usage, tailStay.start, dayEnd))
        list = buildList {
            addAll(list.dropLast(2))
            add(merged)
        }
    }

    return list
}
