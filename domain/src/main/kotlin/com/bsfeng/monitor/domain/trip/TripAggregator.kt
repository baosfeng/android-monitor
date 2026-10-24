package com.bsfeng.monitor.domain.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 每日行程聚合：一天一次、O(n) 单遍（n = 当天记录条数）。
 *
 * 判定阶梯（有定位优先，没有才退化，退化不了就是 Gap）：
 * 1. **GPS 基准**：LOCATION 点按 0.001° 量化聚成「连续同址段」，
 *    段长 ≥10 分钟且相对质心半径 ≤80m → [Stay]；相邻两段停留净位移 >300m
 *    且中途定位覆盖 ≥60% → [Move]（距离按轨迹逐点 haversine 累加）；
 * 2. **DEVICE 基准**（无定位点）：同一 SSID 连续 ≥10 分钟 → 地点、SSID 切换 → 地点变更；
 *    APP_USAGE 间隔 ≤5 分钟合并成使用簇，簇长 ≥10 分钟 → [Stay]；两类候选重叠时合并；
 * 3. 其余一律 [Gap]，不猜内容。
 *
 * 异常与边界（约定写死，避免返工）：
 * - `dayEnd <= dayStart` → 抛 [IllegalArgumentException]（调用方 bug，不静默返回空）；
 * - 落在 `[dayStart, dayEnd)` 之外的记录整条丢弃（跨界记录不做半截推断）；
 * - 空输入 → 空 [DayTimeline]（segments 为空，UI 走空态）；
 * - 定位点时间断档 >1 小时 → 断开同一停留段（不让「盲区」被算成同一地点连续停留）；
 * - 页面记录只在能识别出 App 时计数；无法识别的直接忽略，不产生占位条目。
 */
class TripAggregator(
    internal val dayStart: Long,
    internal val dayEnd: Long = dayStart + TRIP_DAY_MS,
) {

    init {
        require(dayEnd > dayStart) { "dayEnd($dayEnd) 必须大于 dayStart($dayStart)" }
    }

    internal val windowMs: Long get() = dayEnd - dayStart

    fun aggregate(records: List<Record>): DayTimeline {
        if (records.isEmpty()) return DayTimeline(dayStart = dayStart)

        val points = ArrayList<LocationPoint>()
        val usage = ArrayList<UsageSlice>()
        val pages = ArrayList<PageSample>()
        val wifis = ArrayList<WifiSample>()

        // 单遍分类：记录已按时间升序，后续三个分支都不再重新排序
        for (record in records) {
            val ts = record.timestamp
            if (ts < dayStart || ts >= dayEnd) continue
            val name = typeNameOf(record.type)
            when {
                isLocation(record, name) -> record.toPoint()?.let { points.add(it) }
                isPage(record, name) -> record.toPageSample()?.let { pages.add(it) }
                name == TripAggregatorRules.NAME_APP_USAGE -> record.toUsage(dayStart)?.let { usage.add(it) }
                name == TripAggregatorRules.NAME_WIFI -> record.toWifi()?.let { wifis.add(it) }
            }
        }

        return if (points.size >= TripAggregatorRules.MIN_POINTS_FOR_GPS) {
            fromGps(points, usage, pages)
        } else {
            fromDevice(usage, pages, wifis)
        }
    }

    // ───────────────────────────── GPS 基准 ─────────────────────────────

    private fun fromGps(
        points: List<LocationPoint>,
        usage: List<UsageSlice>,
        pages: List<PageSample>,
    ): DayTimeline {
        val runs = buildRuns(points)
        val stayRunIndexes = runs.indices.filter { runs[it].isStay() }
        if (stayRunIndexes.isEmpty()) {
            // 有定位点但没形成停留：全天只可能是「未记录」，不去推断移动
            return DayTimeline(dayStart, listOf(Gap(dayStart, dayEnd)), Basis.GPS, coveragePct = 0)
        }

        val segments = ArrayList<DaySegment>()
        var activeMs = 0L
        var cursor = dayStart
        // 同一地点只发一个 Place：id 用坐标量化串而不是序号，
        // 否则「家 6h32」「家 2h36」会变成两个地点，主地点时长只取最长单段，
        // 「在外」随之把另一段算成在外，两格相加超过一天。
        val registry = PlaceRegistry(TripAggregatorRules.GENERIC_PLACE_LABEL)

        for ((order, runIndex) in stayRunIndexes.withIndex()) {
            val run = runs[runIndex]
            val stayStart = run.start.coerceIn(dayStart, dayEnd)
            val stayEnd = run.end.coerceIn(dayStart, dayEnd)
            if (stayEnd > stayStart) {
                if (stayStart > cursor) segments.add(Gap(cursor, stayStart))
                val place = registry.geo(run.centroidLat, run.centroidLng, run.labelOrNull())
                segments.add(
                    Stay(
                        start = stayStart,
                        end = stayEnd,
                        place = place,
                        topApps = topApps(usage, stayStart, stayEnd),
                        pages = tallyPages(pages, stayStart, stayEnd),
                    ),
                )
                activeMs += stayEnd - stayStart
                cursor = stayEnd
            }

            val nextIndex = stayRunIndexes.getOrNull(order + 1) ?: continue
            val moveStart = cursor
            val moveEnd = runs[nextIndex].start.coerceIn(dayStart, dayEnd)
            if (moveEnd > moveStart) {
                val between = ArrayList<LocationPoint>()
                for (i in runIndex + 1 until nextIndex) between.addAll(runs[i].samples)
                val move = buildMove(
                    start = moveStart,
                    end = moveEnd,
                    from = registry.geo(run.centroidLat, run.centroidLng, run.labelOrNull()),
                    to = registry.geo(
                        runs[nextIndex].centroidLat,
                        runs[nextIndex].centroidLng,
                        runs[nextIndex].labelOrNull(),
                    ),
                    track = between,
                )
                if (move != null) {
                    segments.add(move)
                    activeMs += move.durationMs
                    cursor = moveEnd
                }
            }
        }

        if (cursor < dayEnd) segments.add(Gap(cursor, dayEnd))
        return DayTimeline(dayStart, segments, Basis.GPS, coveragePct(activeMs, windowMs))
    }

    /**
     * 相邻停留之间：净位移 >300m 且定位覆盖 ≥60% 才算移动，否则交回 Gap。
     * 覆盖口径 = 1 - 「采样空档（>1 小时）」/ 区间时长；两端没有采样的空档同样计入空档。
     */
    private fun buildMove(
        start: Long,
        end: Long,
        from: Place,
        to: Place,
        track: List<LocationPoint>,
    ): Move? {
        val span = end - start
        if (span <= 0L) return null

        var far = 0L
        var prev = start
        track.forEach { point ->
            val delta = point.ts - prev
            if (delta > TripAggregatorRules.MAX_SAMPLE_GAP_MS) far += delta
            prev = point.ts
        }
        val tail = end - prev
        if (tail > TripAggregatorRules.MAX_SAMPLE_GAP_MS) far += tail
        val coverage = 1.0 - far.toDouble() / span
        if (coverage < TripAggregatorRules.MOVE_MIN_COVERAGE) return null

        var distance = 0.0
        var lat = from.lat ?: track.firstOrNull()?.lat
        var lng = from.lng ?: track.firstOrNull()?.lng
        if (lat != null && lng != null) {
            track.forEach { point ->
                distance += haversineMeters(lat!!, lng!!, point.lat, point.lng)
                lat = point.lat
                lng = point.lng
            }
            val endLat = to.lat
            val endLng = to.lng
            if (endLat != null && endLng != null) {
                distance += haversineMeters(lat!!, lng!!, endLat, endLng)
            }
        }
        val net = netDisplacement(from, to, track)
        if (net <= TripAggregatorRules.MOVE_MIN_METERS) return null
        if (distance < TripAggregatorRules.MOVE_MIN_METERS) return null
        return Move(start, end, from, to, distance)
    }

    /** 净位移：优先用两端停留的质心；没有坐标时退化成轨迹首末点。 */
    private fun netDisplacement(from: Place, to: Place, track: List<LocationPoint>): Double {
        val aLat = from.lat
        val aLng = from.lng
        val bLat = to.lat
        val bLng = to.lng
        if (aLat != null && aLng != null && bLat != null && bLng != null) {
            return haversineMeters(aLat, aLng, bLat, bLng)
        }
        val first = track.firstOrNull() ?: return 0.0
        val last = track.lastOrNull() ?: return 0.0
        return haversineMeters(first.lat, first.lng, last.lat, last.lng)
    }

    /**
     * 连续同址段：点位按时序累积，离开质心超过 80m 或出现 >1 小时采样断档就断段。
     * 0.001° 量化只用于「同格快速判同址」，真正的距离仍用 haversine 复核。
     */
    private fun buildRuns(points: List<LocationPoint>): List<Run> {
        val runs = ArrayList<Run>()
        var current: Run? = null
        for (point in points) {
            val run = current
            if (run == null) {
                val fresh = Run(point)
                runs.add(fresh)
                current = fresh
                continue
            }
            val sameCell = run.cell == point.cell()
            val farAway = !sameCell &&
                haversineMeters(run.centroidLat, run.centroidLng, point.lat, point.lng) > TripAggregatorRules.STAY_RADIUS_M
            val stale = point.ts - run.lastTs > TripAggregatorRules.MAX_SAMPLE_GAP_MS
            if (farAway || stale) {
                val fresh = Run(point)
                runs.add(fresh)
                current = fresh
            } else {
                run.add(point)
            }
        }
        return runs
    }
}

/** 两点间大圆距离（米）。 */
internal fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * 6_371_000.0 * asin(min(1.0, sqrt(a)))
}
