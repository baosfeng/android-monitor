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
    private val dayStart: Long,
    private val dayEnd: Long = dayStart + TRIP_DAY_MS,
) {

    init {
        require(dayEnd > dayStart) { "dayEnd($dayEnd) 必须大于 dayStart($dayStart)" }
    }

    private val windowMs: Long get() = dayEnd - dayStart

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
                name == NAME_APP_USAGE -> record.toUsage()?.let { usage.add(it) }
                name == NAME_WIFI -> record.toWifi()?.let { wifis.add(it) }
            }
        }

        return if (points.size >= MIN_POINTS_FOR_GPS) {
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
        val registry = PlaceRegistry(GENERIC_PLACE_LABEL)

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
        return DayTimeline(dayStart, segments, Basis.GPS, coveragePct(activeMs))
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
            if (delta > MAX_SAMPLE_GAP_MS) far += delta
            prev = point.ts
        }
        val tail = end - prev
        if (tail > MAX_SAMPLE_GAP_MS) far += tail
        val coverage = 1.0 - far.toDouble() / span
        if (coverage < MOVE_MIN_COVERAGE) return null

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
        if (net <= MOVE_MIN_METERS) return null
        if (distance < MOVE_MIN_METERS) return null
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
                haversineMeters(run.centroidLat, run.centroidLng, point.lat, point.lng) > STAY_RADIUS_M
            val stale = point.ts - run.lastTs > MAX_SAMPLE_GAP_MS
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

    // ──────────────────────────── DEVICE 基准 ────────────────────────────

    private fun fromDevice(
        usage: List<UsageSlice>,
        pages: List<PageSample>,
        wifis: List<WifiSample>,
    ): DayTimeline {
        if (usage.isEmpty() && wifis.isEmpty()) {
            return DayTimeline(dayStart, listOf(Gap(dayStart, dayEnd)), Basis.DEVICE, coveragePct = 0)
        }

        val candidates = ArrayList<StayRange>()
        // 1) 同一 SSID 连续 ≥10 分钟 = 地点
        for (run in buildSsidRuns(wifis)) {
            if (run.end - run.start >= STAY_MIN_MS) {
                candidates.add(StayRange(run.start, run.end, run.ssid, Basis.DEVICE))
            }
        }
        // 2) APP_USAGE 间隔 ≤5 分钟合并成簇，簇长 ≥10 分钟 = 停留
        for (cluster in buildUsageClusters(usage)) {
            if (cluster.end - cluster.start >= STAY_MIN_MS) {
                candidates.add(StayRange(cluster.start, cluster.end, USAGE_PLACE_LABEL, Basis.DEVICE))
            }
        }
        if (candidates.isEmpty()) {
            return DayTimeline(dayStart, listOf(Gap(dayStart, dayEnd)), Basis.DEVICE, coveragePct = 0)
        }

        val merged = mergeRanges(candidates)
        val segments = ArrayList<DaySegment>()
        var activeMs = 0L
        var cursor = dayStart
        val registry = PlaceRegistry(USAGE_PLACE_LABEL)
        merged.forEachIndexed { index, range ->
            val start = range.start.coerceIn(dayStart, dayEnd)
            val end = range.end.coerceIn(dayStart, dayEnd)
            if (end <= start) return@forEachIndexed
            if (start > cursor) segments.add(Gap(cursor, start))
            // SSID 同名即同一地点；使用簇没有网络名，统一归到「设备活跃」这一个退化标识
            val place = if (range.label == USAGE_PLACE_LABEL) {
                registry.device(DEVICE_ACTIVITY_ID, USAGE_PLACE_LABEL)
            } else {
                registry.device(SSID_ID_PREFIX + range.label, range.label)
            }
            segments.add(
                Stay(
                    start = start,
                    end = end,
                    place = place,
                    topApps = topApps(usage, start, end),
                    pages = tallyPages(pages, start, end),
                ),
            )
            activeMs += end - start
            cursor = end
        }
        if (cursor < dayEnd) segments.add(Gap(cursor, dayEnd))
        return DayTimeline(dayStart, segments, Basis.DEVICE, coveragePct(activeMs))
    }

    /** 同一 SSID 的连续区间；遇到别的 SSID / 断开 / 窗口结束即为区间终点。 */
    private fun buildSsidRuns(samples: List<WifiSample>): List<SsidRun> {
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

    /** 使用簇：按 [USAGE_MERGE_GAP_MS] 合并相邻使用段。 */
    private fun buildUsageClusters(usage: List<UsageSlice>): List<UsageCluster> {
        val clusters = ArrayList<UsageCluster>()
        var current: UsageCluster? = null
        for (slice in usage) {
            val c = current
            if (c == null || slice.start - c.end > USAGE_MERGE_GAP_MS) {
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
    private fun mergeRanges(ranges: List<StayRange>): List<StayRange> {
        val sorted = ranges.sortedBy { it.start }
        val merged = ArrayList<StayRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.start < last.end) {
                if (range.end > last.end) last.end = range.end
                if (last.label == USAGE_PLACE_LABEL && range.label != USAGE_PLACE_LABEL) {
                    last.label = range.label
                }
            } else {
                merged.add(StayRange(range.start, range.end, range.label, range.source))
            }
        }
        return merged
    }

    // ────────────────────────────── 期间明细 ──────────────────────────────

    /**
     * 停留期间 App 使用时长：把使用段裁到 [start, end) 后按 App 聚合，**按降序返回全部**。
     * 抽屉要能看全；摘要/抽屉前若干条由 UI 侧自行截取（见 `visibleApps`）。
     */
    private fun topApps(usage: List<UsageSlice>, start: Long, end: Long): List<UsageSlice> {
        if (usage.isEmpty() || end <= start) return emptyList()
        val acc = LinkedHashMap<String, UsageSlice>()
        var i = lowerBoundByStart(usage, start)
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
    private fun tallyPages(pages: List<PageSample>, start: Long, end: Long): List<PageTally> {
        if (pages.isEmpty() || end <= start) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        var i = lowerBoundByTs(pages, start)
        while (i < pages.size && pages[i].ts < end) {
            val app = pages[i].app
            counts[app] = (counts[app] ?: 0) + 1
            i++
        }
        return counts.entries
            .sortedByDescending { it.value }
            .map { PageTally(it.key, it.value) }
    }

    private fun coveragePct(activeMs: Long): Int {
        if (windowMs <= 0L) return 0
        return ((activeMs * 100) / windowMs).coerceIn(0L, 100L).toInt()
    }

    // ────────────────────────────── 解析辅助 ──────────────────────────────

    private fun Record.toPoint(): LocationPoint? {
        val d = detail ?: return null
        val lat = d["lat"]?.toDoubleOrNull() ?: return null
        val lng = d["lng"]?.toDoubleOrNull() ?: return null
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null
        return LocationPoint(
            lat = lat,
            lng = lng,
            ts = timestamp,
            label = d["address"]?.takeIf { it.isNotBlank() },
            accuracy = (d["accuracy_m"] ?: d["accuracy"])?.toDoubleOrNull()?.takeIf { it > 0.0 },
        )
    }

    private fun Record.toUsage(): UsageSlice? {
        val ms = duration ?: detail?.get("duration_ms")?.toLongOrNull() ?: return null
        if (ms <= 0L) return null
        val app = label.ifBlank { detail?.get("app_name").orEmpty() }.ifBlank { UNKNOWN_APP }
        return UsageSlice(
            app = app,
            pkg = detail?.get("package_name")?.takeIf { it.isNotBlank() },
            start = (timestamp - ms).coerceAtLeast(dayStart),
            end = timestamp,
            ms = ms,
        )
    }

    private fun Record.toPageSample(): PageSample? {
        val app = detail?.get("app_name")?.takeIf { it.isNotBlank() }
            ?: label.substringBefore('›').trim().ifBlank { null }
            ?: return null
        return PageSample(app, timestamp)
    }

    private fun Record.toWifi(): WifiSample? {
        val ssid = detail?.get("ssid")?.takeIf { it.isNotBlank() }
            ?: label.substringAfter("WiFi", "").trim().trimStart('：', ':').ifBlank { null }
            ?: return null
        return WifiSample(ssid, timestamp, action == "DISCONNECTED")
    }

    private fun isLocation(record: Record, name: String): Boolean {
        if (name == NAME_LOCATION) return true
        val d = record.detail ?: return false
        return d["lat"] != null && d["lng"] != null
    }

    private fun isPage(record: Record, name: String): Boolean {
        if (name == NAME_PAGE) return true
        return record.detail?.containsKey("page_key") == true
    }

    /**
     * 类型名走 `::class.simpleName`：PAGE 由并行任务在 `RecordType` 里新增，
     * 这里按名字识别，聚合层不依赖它的编译期符号（避免与另一路的改动互相锁死）。
     */
    private fun typeNameOf(type: RecordType): String = type::class.simpleName ?: ""

    // ────────────────────────────── 内部数据结构 ──────────────────────────────

    private class LocationPoint(
        val lat: Double,
        val lng: Double,
        val ts: Long,
        val label: String? = null,
        /** 采集精度（米）；缺失为 null。地名平票时用它挑更可信的那个。 */
        val accuracy: Double? = null,
    ) {
        fun cell(): Long {
            // 0.001° ≈ 111m（纬度）/ 100m（赤道经度）：只用来判「同格」，距离仍由 haversine 复核
            val latQ = Math.round(lat * 1000.0)
            val lngQ = Math.round(lng * 1000.0)
            return latQ * 1_000_000L + lngQ
        }
    }

    /**
     * 聚类内地名投票。
     *
     * 为什么不是「取第一个采样」：移动段的末点（ratio=1）会落在目标地点 300m 内被并进停留簇，
     * 而它的地址是「移动中」这类瞬态占位——按首个采样取，真地名就被占位顶掉了。
     * 规则：先剔除瞬态占位与空白，再取出现次数最多者；平票时取 accuracy 更小（更准）的点。
     */
    private class LabelTally {
        private val counts = LinkedHashMap<String, Int>()
        private val bestAccuracy = HashMap<String, Double>()

        fun add(label: String?, accuracy: Double?) {
            val name = label?.trim().orEmpty()
            if (name.isEmpty() || name in TRANSIENT_PLACE_LABELS) return
            counts[name] = (counts[name] ?: 0) + 1
            if (accuracy != null && accuracy > 0.0) {
                val prev = bestAccuracy[name]
                if (prev == null || accuracy < prev) bestAccuracy[name] = accuracy
            }
        }

        fun best(): String? = counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { bestAccuracy[it.key] ?: Double.MAX_VALUE },
            )
            .firstOrNull()
            ?.key
    }

    private class Run(first: LocationPoint) {
        val samples = ArrayList<LocationPoint>()
        var start: Long = first.ts
            private set
        var end: Long = first.ts
            private set
        var lastTs: Long = first.ts
            private set
        var cell: Long = first.cell()
            private set
        private var sumLat: Double = first.lat
        private var sumLng: Double = first.lng
        private var count: Int = 1
        private var maxDeviation: Double = 0.0
        private val labels = LabelTally()

        init {
            samples.add(first)
            labels.add(first.label, first.accuracy)
        }

        val centroidLat: Double get() = sumLat / count
        val centroidLng: Double get() = sumLng / count

        fun add(point: LocationPoint) {
            samples.add(point)
            sumLat += point.lat
            sumLng += point.lng
            count++
            end = point.ts
            lastTs = point.ts
            cell = point.cell()
            labels.add(point.label, point.accuracy)
            val deviation = haversineMeters(centroidLat, centroidLng, point.lat, point.lng)
            if (deviation > maxDeviation) maxDeviation = deviation
        }

        fun isStay(): Boolean = end - start >= STAY_MIN_MS && maxDeviation <= STAY_RADIUS_M

        /** 聚类内的地名：出现次数最多者；平票取精度更好的那个。 */
        fun labelOrNull(): String? = labels.best()
    }

    /**
     * 地点登记表：同一份时间轴内**同一地点只发一个 [Place]**（id 稳定，不随段序号变化）。
     *
     * - GPS：`geo:<lat3>,<lng3>`（0.001° ≈ 111m，与聚类网格同尺度）；
     *   另外按 80m 半径做一次归并，避免两次访问恰好落在量化格两侧被当成两个地点；
     * - DEVICE：`ssid:<网络名>`；使用簇退化地点统一为 `device:activity`。
     *
     * 地点名取**首次出现的有效名字**；首次没名字、后续段有名字时回填，不会一直显示通用占位。
     */
    private class PlaceRegistry(private val fallbackLabel: String) {
        private val places = ArrayList<Place>()

        fun geo(lat: Double, lng: Double, label: String?): Place {
            val existing = places.firstOrNull { place ->
                val pLat = place.lat
                val pLng = place.lng
                pLat != null && pLng != null && haversineMeters(pLat, pLng, lat, lng) <= STAY_RADIUS_M
            }
            if (existing != null) {
                if (existing.label == fallbackLabel && label != null) {
                    val renamed = existing.copy(label = label)
                    places[places.indexOf(existing)] = renamed
                    return renamed
                }
                return existing
            }
            val place = Place(
                id = GEO_ID_PREFIX + String.format(Locale.US, "%.3f,%.3f", lat, lng),
                label = label ?: fallbackLabel,
                lat = lat,
                lng = lng,
                source = Basis.GPS,
            )
            places.add(place)
            return place
        }

        fun device(id: String, label: String): Place {
            places.firstOrNull { it.id == id }?.let { return it }
            val place = Place(id = id, label = label, source = Basis.DEVICE)
            places.add(place)
            return place
        }
    }

    private data class PageSample(val app: String, val ts: Long)

    private data class WifiSample(val ssid: String, val ts: Long, val disconnect: Boolean)

    private class SsidRun(val start: Long, val end: Long, val ssid: String)

    private class UsageCluster(var start: Long, var end: Long)

    private class StayRange(
        var start: Long,
        var end: Long,
        var label: String,
        val source: Basis,
    )

    private companion object {
        const val NAME_LOCATION = "LOCATION"
        const val NAME_PAGE = "PAGE"
        const val NAME_APP_USAGE = "APP_USAGE"
        const val NAME_WIFI = "WIFI"

        /** 少于 3 个定位点无法支撑「连续 10 分钟」，直接退化到 DEVICE 基准。 */
        const val MIN_POINTS_FOR_GPS = 3

        const val STAY_MIN_MS = 10 * 60_000L
        const val STAY_RADIUS_M = 80.0
        const val MOVE_MIN_METERS = 300.0
        const val MOVE_MIN_COVERAGE = 0.6
        const val MAX_SAMPLE_GAP_MS = 60 * 60_000L
        const val USAGE_MERGE_GAP_MS = 5 * 60_000L

        const val UNKNOWN_APP = "未知应用"
        const val USAGE_PLACE_LABEL = "使用中"
        const val GEO_ID_PREFIX = "geo:"
        const val SSID_ID_PREFIX = "ssid:"
        const val DEVICE_ACTIVITY_ID = "device:activity"
        const val GENERIC_PLACE_LABEL = "地点"

        /** 移动段/过渡态的地址占位：不参与地名投票，避免把停留簇的地名顶成「移动中」。 */
        val TRANSIENT_PLACE_LABELS = setOf("移动中", "路上", "行驶中", "骑行中")

        fun lowerBoundByStart(list: List<UsageSlice>, start: Long): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].start < start) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun lowerBoundByTs(list: List<PageSample>, ts: Long): Int {
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].ts < ts) lo = mid + 1 else hi = mid
            }
            return lo
        }
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
