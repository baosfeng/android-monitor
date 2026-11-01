package com.bsfeng.monitor.domain.trip

// 从 domain/trip/TripAggregator.kt 拆出：GPS 基准的鲁棒化三件套。
// 1) 单点漂移剔除 dropOutliers：兜底定位（network）周期性跳出又立刻跳回，
//    旧实现按 >STAY_RADIUS_M 直接断段，把同一地点的连续停留切成多段、中间落成 Gap；
// 2) 同址停留块合并 buildStayBlocks：相邻两段停留之间没有产出 Move 且登记表判为同一地点 → 合成一段；
// 3) Gap 成因分级 gapReason：区间内有采样 = UNRESOLVED，一条采样都没有 = INTERRUPTED。

// ──────────────────────────── 单点漂移剔除 ────────────────────────────

/**
 * 剔除「跳出去又立刻跳回」的单点漂移：前后相邻点都在 80m 半径外，
 * 而这两点彼此相距不超过 1.5 倍半径（同一处被一个假点夹在中间）。
 *
 * 三点不变量（改判定前先读）：
 * - 首尾两点不参与判定（没有两侧邻居，剔了就没有参照）；
 * - 邻居取**原始序列**（不是已保留的点），因此连续异常点不会连坐：
 *   两个连续同址的假点彼此距离为 0，第二条件不成立，两者都保留——那是真的挪了位置；
 * - 真实移动点也不剔除：连续过渡的点之间跨步 >80m，但前后两点相距 >1.5 倍半径。
 */
internal fun dropOutliers(points: List<LocationPoint>): List<LocationPoint> {
    if (points.size < 3) return points
    val kept = ArrayList<LocationPoint>(points.size)
    kept.add(points[0])
    for (i in 1 until points.size - 1) {
        val prev = points[i - 1]
        val cur = points[i]
        val next = points[i + 1]
        val jumpedOut = haversineMeters(prev.lat, prev.lng, cur.lat, cur.lng) >
            TripAggregatorRules.STAY_RADIUS_M
        val jumpedBack = haversineMeters(cur.lat, cur.lng, next.lat, next.lng) >
            TripAggregatorRules.STAY_RADIUS_M
        val bridged = haversineMeters(prev.lat, prev.lng, next.lat, next.lng) <=
            TripAggregatorRules.STAY_RADIUS_M * TripAggregatorRules.OUTLIER_BRIDGE_RATIO
        if (jumpedOut && jumpedBack && bridged) continue
        kept.add(cur)
    }
    kept.add(points.last())
    return kept
}

// ──────────────────────────── Gap 成因分级 ────────────────────────────

/**
 * 区间内是否有定位采样：二分找第一个 ts 严格大于 start 的点，落在 end 之前即算有采样。
 *
 * 左端点必须排除：Gap 的 start 恒等于前一段（停留 / 移动）的终点，那个时刻的采样
 * 已经被前一段用掉了，算进来会把每一段末 Gap 都错标成 UNRESOLVED。
 */
internal fun gapReason(points: List<LocationPoint>, start: Long, end: Long): GapReason {
    if (end <= start || points.isEmpty()) return GapReason.INTERRUPTED
    var lo = 0
    var hi = points.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].ts <= start) lo = mid + 1 else hi = mid
    }
    val hit = points.getOrNull(lo) ?: return GapReason.INTERRUPTED
    return if (hit.ts < end) GapReason.UNRESOLVED else GapReason.INTERRUPTED
}

// ──────────────────────── 同址停留块合并 ────────────────────────

/** 一段待发布的停留（来自一个 Run）。 */
internal class StaySpan(
    val start: Long,
    val end: Long,
    val place: Place,
)

/** 合并后的停留块；[followingMove] 是紧跟本块之后的移动（null = 判不出，交给 Gap）。 */
internal class StayBlock(
    val start: Long,
    val end: Long,
    val place: Place,
    val followingMove: Move?,
)

/**
 * 相邻两段停留之间没有产出 Move、且 PlaceRegistry 判为同一地点 → 合并成一段
 * （start 取前段、end 取后段）。净位移 >300m 的真实换地点会先产出 Move，因此不会被合并。
 */
internal fun buildStayBlocks(spans: List<StaySpan>, moves: List<Move?>): List<StayBlock> {
    if (spans.isEmpty()) return emptyList()
    val blocks = ArrayList<StayBlock>(spans.size)
    var start = spans[0].start
    var end = spans[0].end
    var place = spans[0].place
    for (i in 1 until spans.size) {
        val span = spans[i]
        val move = moves.getOrNull(i - 1)
        if (move == null && place.id == span.place.id) {
            end = maxOf(end, span.end)
            continue
        }
        blocks.add(StayBlock(start, end, place, move))
        start = span.start
        end = span.end
        place = span.place
    }
    blocks.add(StayBlock(start, end, place, null))
    return blocks
}
