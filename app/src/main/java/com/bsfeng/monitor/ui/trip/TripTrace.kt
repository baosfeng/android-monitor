package com.bsfeng.monitor.ui.trip

// 每日定位轨迹的纯逻辑：LOCATION 记录 → 采样点 → 画布坐标。
// 不依赖 Android / Compose，可在 JVM 单测里直接验证（TripTraceTest）。
//
// 口径：
// - 只取 LOCATION 记录（detail: lat / lng / address / accuracy_m），按 timestamp 升序；
// - 剔除物理上不可能的跳点 → 精度加权平滑压掉静止漂移 → 相邻 < 5m 去重 → 几何抽稀
//   → 按上限均匀抽稀到 500 点（保留首尾）；
// - 投影用等距圆柱（x = lng · cos(meanLat)，y = -lat），等比缩放到画布并留 padding；
// - 单点或全部重合时居中；画布宽高非法（<= 0）直接返回空投影，不产生 NaN / 负坐标。

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.trip.Stay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 一个轨迹采样点（与数据库记录解耦，便于单测）。
 *
 * [accuracyM] 是该点的定位精度（米），缺精度时（老记录 / provider 没给）为 null：
 * 平滑用它做权重，null 时按 [TripDenoise] 的默认噪声地板处理，不参与任何硬门槛。
 */
data class TraceSample(
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val label: String? = null,
    val accuracyM: Float? = null,
)

/** 画布坐标：x 向右、y 向下（与 Canvas 一致）。 */
data class TracePoint(val x: Float, val y: Float, val timestamp: Long)

/**
 * 停留锚点。
 *
 * [radiusDp] 按停留时长在 6..14dp 之间插值；[labelVisible] = false 表示标签因重叠或超过
 * 6 个被省略（圆仍画，只是不写名字）。
 */
data class TraceStay(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val label: String,
    val labelVisible: Boolean,
    val durationMs: Long,
)

/** 投影结果；空轨迹时两个列表都为空。 */
data class TraceProjection(
    val points: List<TracePoint> = emptyList(),
    val stays: List<TraceStay> = emptyList(),
) {
    val hasTrace: Boolean get() = points.isNotEmpty()
}

object TripTrace {

    /** 最多画 500 个点：再密的采样在 180dp 高的画布上也看不出差别。 */
    const val MAX_POINTS = 500

    /** 相邻两点小于 5m 视为原地抖动（GPS 静止漂移量级）。 */
    const val DEDUP_METERS = 5.0

    /** 停留标签最多 6 个，其余只画圆不写名字。 */
    const val MAX_STAY_LABELS = 6

    const val MIN_STAY_RADIUS_DP = 6f
    const val MAX_STAY_RADIUS_DP = 14f

    private const val EARTH_METERS_PER_DEGREE = 111_320.0

    /**
     * 记录 → 采样点（升序 → 离群剔除 → 平滑 → 去重 → 几何抽稀 → 上限）。
     *
     * 为什么是这个顺序：
     * - 先按时间升序，离群判据才有「上一点」可比；
     * - 离群剔除要在去重之前：跳变点和上一点往往离得很远，先去重不会影响它、但先剔除能少算一遍；
     * - 平滑要在去重之前：静止漂移的点彼此相隔十几米、去重管不到，只有先平均回真值附近，
     *   去重才有机会把它们收拢；反之先去重会先把「能用来平均的邻居」丢掉；
     * - 去重要在抽稀之前：重合点的垂距为 0，留着只会让抽稀多做无用的段判定；
     * - 抽稀按几何误差（Douglas–Peucker）而不是等步长：等步长会把真实拐点抹掉、却保留直线上的冗余点。
     */
    fun samples(records: List<Record>): List<TraceSample> {
        val parsed = parse(records)
        val accuracies = accuracies(parsed)
        val cleaned = TripDenoise.rejectOutliers(parsed, accuracies)
        val smoothed = TripDenoise.smooth(cleaned, accuracies)
        return cap(TripDenoise.simplify(dedupe(smoothed)))
    }

    /** 时间戳 → 精度（米）；只有 accuracy_m > 0 的点才参与离群判定与平滑权重。 */
    private fun accuracies(samples: List<TraceSample>): Map<Long, Float> = samples.mapNotNull { sample ->
        val accuracy = sample.accuracyM?.takeIf { it > 0f && !it.isNaN() } ?: return@mapNotNull null
        sample.timestamp to accuracy
    }.toMap()

    private fun parse(records: List<Record>): List<TraceSample> = records
        .mapNotNull { sampleOf(it) }
        .sortedBy { it.timestamp }

    private fun sampleOf(record: Record): TraceSample? {
        val detail = record.detail ?: return null
        val lat = detail["lat"]?.toDoubleOrNull() ?: return null
        val lng = detail["lng"]?.toDoubleOrNull() ?: return null
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null
        return TraceSample(
            timestamp = record.timestamp,
            lat = lat,
            lng = lng,
            label = detail["address"]?.takeIf { it.isNotBlank() },
            accuracyM = detail["accuracy_m"]?.toFloatOrNull()?.takeIf { it > 0f && !it.isNaN() },
        )
    }

    /** 相邻 < [DEDUP_METERS] 的点丢掉（保留每个位置上的第一个点）。 */
    internal fun dedupe(samples: List<TraceSample>): List<TraceSample> {
        if (samples.size < 2) return samples
        val out = ArrayList<TraceSample>(samples.size)
        out.add(samples.first())
        for (i in 1 until samples.size) {
            val prev = out[out.lastIndex]
            val next = samples[i]
            if (meters(prev.lat, prev.lng, next.lat, next.lng) >= DEDUP_METERS) out.add(next)
        }
        return out
    }

    /**
     * 超过 [MAX_POINTS] 时按步长均匀抽稀，首尾必留。
     *
     * 这是**最后一道保险**，不是主抽稀手段：主抽稀是 [TripDenoise.simplify] 的几何抽稀，
     * 正常一天的轨迹经它之后远小于 500 点；只有极端密集数据（如补采风暴）才会走到这里。
     */
    internal fun cap(samples: List<TraceSample>): List<TraceSample> {
        if (samples.size <= MAX_POINTS) return samples
        val step = (samples.size - 1).toDouble() / (MAX_POINTS - 1)
        val indices = HashSet<Int>(MAX_POINTS)
        val out = ArrayList<TraceSample>(MAX_POINTS)
        for (i in 0 until MAX_POINTS) {
            val index = (i * step).roundToInt().coerceIn(0, samples.lastIndex)
            if (indices.add(index)) out.add(samples[index])
        }
        if (out.last().timestamp != samples.last().timestamp) out[out.lastIndex] = samples.last()
        return out
    }

    /** 等距圆柱近似下的两点距离（米）。 */
    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
        val dx = (lng2 - lng1) * cos(meanLat) * EARTH_METERS_PER_DEGREE
        val dy = (lat2 - lat1) * EARTH_METERS_PER_DEGREE
        return hypot(dx, dy)
    }

    /**
     * 投影到画布。
     *
     * @param widthPx / heightPx 画布尺寸（px）；<= 0 返回空投影
     * @param paddingPx 四周留白（px）
     * @param charPx 标签单字宽度估计（px），用于重叠判定；默认值只给单测用，屏内传实测
     * @param labelHeightPx 标签行高估计（px）
     */
    fun project(
        samples: List<TraceSample>,
        stays: List<Stay> = emptyList(),
        widthPx: Float,
        heightPx: Float,
        paddingPx: Float,
        charPx: Float = 13f,
        labelHeightPx: Float = 16f,
        density: Float = 1f,
    ): TraceProjection {
        if (samples.isEmpty() || widthPx <= 0f || heightPx <= 0f) return TraceProjection()
        val pad = paddingPx.coerceAtLeast(0f).coerceAtMost(min(widthPx, heightPx) / 2f)
        val meanLat = samples.sumOf { it.lat } / samples.size
        val cosLat = cos(Math.toRadians(meanLat))
        val rawX = samples.map { it.lng * cosLat }
        val rawY = samples.map { -it.lat }
        val minX = rawX.min()
        val maxX = rawX.max()
        val minY = rawY.min()
        val maxY = rawY.max()
        val spanX = maxX - minX
        val spanY = maxY - minY
        val innerW = (widthPx - 2f * pad).coerceAtLeast(0f)
        val innerH = (heightPx - 2f * pad).coerceAtLeast(0f)

        // 全部重合（或只有一个点）：居中，不缩放
        if (spanX < EPS && spanY < EPS) {
            val cx = widthPx / 2f
            val cy = heightPx / 2f
            val single = samples.map { TracePoint(cx, cy, it.timestamp) }
            return TraceProjection(
                single,
                projectStaysAt(
                    located = locatedStays(stays),
                    widthPx = widthPx,
                    heightPx = heightPx,
                    density = density,
                    charPx = charPx,
                    labelHeightPx = labelHeightPx,
                ) { _, _ -> cx to cy },
            )
        }
        // 等比缩放：取两个方向里更紧的那个；单方向无跨度时按另一方向铺满
        val scale = when {
            spanX < EPS -> innerH / spanY
            spanY < EPS -> innerW / spanX
            else -> min(innerW / spanX, innerH / spanY)
        }
        val drawnW = (spanX * scale).toFloat()
        val drawnH = (spanY * scale).toFloat()
        val offsetX = (widthPx - drawnW) / 2f
        val offsetY = (heightPx - drawnH) / 2f
        val points = samples.mapIndexed { i, sample ->
            TracePoint(
                x = offsetX + ((rawX[i] - minX) * scale).toFloat(),
                y = offsetY + ((rawY[i] - minY) * scale).toFloat(),
                timestamp = sample.timestamp,
            )
        }
        val located = locatedStays(stays)
        val locatedCos = meanCosLat(located)
        return TraceProjection(
            points = points,
            stays = projectStaysAt(
                located = located,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                charPx = charPx,
                labelHeightPx = labelHeightPx,
            ) { lat, lng ->
                val x = offsetX + ((lng * locatedCos - minX) * scale).toFloat()
                val y = offsetY + ((-lat - minY) * scale).toFloat()
                x to y
            },
        )
    }

    /**
     * 底图模式下的投影：轨迹点与停留点都走 [TileView] 的 Mercator 映射。
     *
     * 与 [project] 的区别只在「缩放怎么定」——这里由地图级别定，点必须跟瓦片严丝合缝；
     * 半径、标签避让、短名等口径与无底图时完全一致。
     */
    fun projectOnMap(
        samples: List<TraceSample>,
        stays: List<Stay> = emptyList(),
        view: TileView,
        widthPx: Float,
        heightPx: Float,
        density: Float = 1f,
        charPx: Float = 13f,
        labelHeightPx: Float = 16f,
    ): TraceProjection {
        if (samples.isEmpty() || widthPx <= 0f || heightPx <= 0f) return TraceProjection()
        val points = samples.map { sample ->
            TracePoint(
                x = (TileMath.canvasX(sample.lng, view) * density).toFloat(),
                y = (TileMath.canvasY(sample.lat, view) * density).toFloat(),
                timestamp = sample.timestamp,
            )
        }
        return TraceProjection(
            points = points,
            stays = projectStaysAt(
                located = locatedStays(stays),
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                charPx = charPx,
                labelHeightPx = labelHeightPx,
            ) { lat, lng ->
                (TileMath.canvasX(lng, view) * density).toFloat() to
                    (TileMath.canvasY(lat, view) * density).toFloat()
            },
        )
    }

    /** 有坐标的停留（没有坐标的停留只留在时间轴里，不画圆）。 */
    private fun locatedStays(stays: List<Stay>): List<Stay> =
        stays.filter { it.place.lat != null && it.place.lng != null }

    /** 这批停留点的平均纬度余弦：等距圆柱近似的经度压缩比。 */
    private fun meanCosLat(located: List<Stay>): Double =
        if (located.isEmpty()) 1.0 else cos(Math.toRadians(located.sumOf { it.place.lat!! } / located.size))

    /**
     * 停留点投影 + 标签重叠剔除。
     *
     * [map] 只负责「经纬度 → 画布 px」，两种画法（自适应缩放 / 底图 Mercator）共用半径与避让逻辑。
     */
    private fun projectStaysAt(
        located: List<Stay>,
        widthPx: Float,
        heightPx: Float,
        density: Float,
        charPx: Float,
        labelHeightPx: Float,
        map: (lat: Double, lng: Double) -> Pair<Float, Float>,
    ): List<TraceStay> {
        if (located.isEmpty()) return emptyList()
        val maxDuration = located.maxOf { it.durationMs }.coerceAtLeast(0L)
        val centers = located.map { stay ->
            val (x, y) = map(stay.place.lat!!, stay.place.lng!!)
            TraceStay(
                x = x,
                y = y,
                radiusDp = stayRadiusDp(stay.durationMs, maxDuration),
                label = shortLabel(stay.place.label),
                labelVisible = false,
                durationMs = stay.durationMs,
            )
        }
        // 重叠判定按「停留时长降序」贪心：长得住的标签优先保住，最多 6 个
        val kept = ArrayList<Box>(MAX_STAY_LABELS)
        val visible = BooleanArray(centers.size)
        centers.indices.sortedByDescending { centers[it].durationMs }.forEach { index ->
            if (kept.size >= MAX_STAY_LABELS) return@forEach
            val marker = centers[index]
            if (marker.x < 0f || marker.y < 0f || marker.x > widthPx || marker.y > heightPx) return@forEach
            val radiusPx = marker.radiusDp * density
            val box = Box(
                left = marker.x - radiusPx,
                right = marker.x + radiusPx + marker.label.length * charPx,
                top = marker.y - radiusPx - labelHeightPx,
                bottom = marker.y + radiusPx,
            )
            if (kept.none { it.overlaps(box) }) {
                kept.add(box)
                visible[index] = true
            }
        }
        return centers.mapIndexed { i, stay -> stay.copy(labelVisible = visible[i]) }
    }

    private fun stayRadiusDp(durationMs: Long, maxDurationMs: Long): Float {
        if (maxDurationMs <= 0L) return MIN_STAY_RADIUS_DP
        val ratio = (durationMs.toDouble() / maxDurationMs).coerceIn(0.0, 1.0)
        return MIN_STAY_RADIUS_DP + (MAX_STAY_RADIUS_DP - MIN_STAY_RADIUS_DP) * ratio.toFloat()
    }

    /** 地址去省 / 市 / 区县前缀，最多 12 字；去不干净时原样返回。 */
    fun shortLabel(raw: String): String {
        var text = raw.trim()
        repeat(3) {
            val index = text.indexOfFirst { it in ADMIN_SUFFIXES }
            if (index in 0..6 && text.length - index - 1 >= 2) {
                text = text.substring(index + 1).trim()
            }
        }
        return text.take(12).ifBlank { raw }
    }

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    private const val EPS = 1e-9
    // 只认「省 / 市 / 区 / 县」四级后缀：'州' 会误伤「杭州」「苏州」这类地名
    private val ADMIN_SUFFIXES = charArrayOf('省', '市', '区', '县')
}
