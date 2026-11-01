package com.bsfeng.monitor.ui.trip

// 轨迹清洗：离群点剔除 + 精度加权平滑 + 按几何误差抽稀（Douglas–Peucker）。
// 纯逻辑、不依赖 Android / Compose，可在纯 JVM 单测里逐条验证（TripDenoiseTest）。
//
// 为什么需要这一层（对应「定位过程中画的地图不准」）：
// - 定位点带 accuracy，室内 NETWORK 常到几百米，这种点会把折线拽出一个尖角；
// - 相邻两点时间差 10 秒，实际位移却上千米 —— 物理上不可能，是 provider 切换或基站抖动；
// - 静止漂移的点围着真值乱跳，抽稀只会保留极值顶点，锯齿被原样画出来 —— 只有平滑能压掉；
// - 原始点密到几十米一个，画在 180dp 高的画布上就是一条锯齿，看不出路网走向。

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 轨迹清洗：离群点剔除 + 几何抽稀。 */
object TripDenoise {

    /** 时间差再大也按这个上限算（秒），避免隔夜断档把速度门槛放到无穷。 */
    private const val MAX_GAP_SECONDS = 600.0

    /** 时间差为 0 时按 1 秒算，避免除零。 */
    private const val MIN_GAP_SECONDS = 1.0

    /** 可接受速度（米/秒）：36 m/s ≈ 130 km/h，覆盖高铁；超过就是物理上不可能的跳变。 */
    private const val MAX_SPEED_MPS = 36.0

    /** 精度容差倍数：位移在「两倍精度圈」以内都算同一个位置的正常抖动。 */
    private const val ACCURACY_SLACK = 2.0

    /** 无论如何都放行的位移（米）：这个尺度内的点不可能是噪点。 */
    private const val ALWAYS_KEEP_METERS = 60.0

    /** 抽稀阈值相对轨迹尺度的分母（包围盒对角线 / N）。 */
    private const val SIMPLIFY_SCALE_DIVISOR = 150.0

    /** 抽稀阈值的下界（米）：比这更小的偏差在 180dp 画布上看不出来。 */
    private const val SIMPLIFY_MIN_METERS = 1.5

    /** 抽稀阈值的上界（米）：再粗就会把真实拐弯抹平。 */
    private const val SIMPLIFY_MAX_METERS = 40.0

    /** 平滑窗口的时间半宽（毫秒）：跨度再大，两端就不是「同一个位置」了。 */
    private const val SMOOTH_HALF_WINDOW_MS = 60_000L

    /** 平滑窗口的点数半宽：窗口最多 5 个点，再宽会把真实拐弯磨圆。 */
    private const val SMOOTH_HALF_WINDOW_POINTS = 2

    /** 跨这个时长（5 分钟）就是断档：两侧不属于同一次移动，平滑会把断档两头拉近。 */
    private const val SMOOTH_MAX_GAP_MS = 300_000L

    /** 缺精度时按这个噪声地板算权重（米）。 */
    private const val DEFAULT_ACCURACY_METERS = 50.0

    /** 精度下限（米）：防止 1/accuracy² 权重因 0.x 米精度炸掉。 */
    private const val MIN_ACCURACY_METERS = 1.0

    /** 拐点保护的地板（米）：小于这个偏移在 180dp 画布上看不出来。 */
    private const val TURN_PROTECT_MIN_METERS = 10.0

    /** 拐点保护的噪声倍数：偏离弦超过「噪声地板 × 这个倍数」就不是抖动，是真几何。 */
    private const val TURN_PROTECT_NOISE_FACTOR = 2.0

    /** 一度纬度对应的米数（等距圆柱近似，与 [TripTrace.meters] 同口径）。 */
    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * 剔除离群点。
     *
     * 判据：相邻两点位移 > max([ALWAYS_KEEP_METERS], 速度上限 × 时间差 + 两倍精度圈)。
     * 命中时丢**后**一点（跳变后的点），保留前一点 —— 位置上「原来在哪」比「跳到哪」更可信。
     *
     * 连续跳变时逐个判定，一长串坏点都会被剔掉，不会因为丢了一个就断链。
     *
     * @param accuracies 时间戳 → 精度（米）；缺这个点的精度时该段直接放行（没有判据就不要猜）
     */
    fun rejectOutliers(
        samples: List<TraceSample>,
        accuracies: Map<Long, Float> = emptyMap(),
    ): List<TraceSample> {
        if (samples.size < 2) return samples
        val out = ArrayList<TraceSample>(samples.size)
        out.add(samples.first())
        for (index in 1 until samples.size) {
            val candidate = samples[index]
            if (isPlausible(out[out.lastIndex], candidate, accuracies)) out.add(candidate)
        }
        return out
    }

    /** 单次位移是否物理上可能（任一端缺精度信息时一律放行）。 */
    internal fun isPlausible(
        previous: TraceSample,
        candidate: TraceSample,
        accuracies: Map<Long, Float>,
    ): Boolean {
        val accuracyA = accuracies[previous.timestamp] ?: return true
        val accuracyB = accuracies[candidate.timestamp] ?: return true
        val gapSeconds = ((candidate.timestamp - previous.timestamp).toDouble() / 1000.0)
            .coerceIn(MIN_GAP_SECONDS, MAX_GAP_SECONDS)
        val slack = ACCURACY_SLACK * (accuracyA.toDouble() + accuracyB.toDouble())
        val allowed = max(ALWAYS_KEEP_METERS, MAX_SPEED_MPS * gapSeconds + slack)
        val distance = TripTrace.meters(previous.lat, previous.lng, candidate.lat, candidate.lng)
        return distance <= allowed
    }

    /**
     * 精度加权滑动平均：把静止漂移的锯齿压平。
     *
     * 权重 ∝ 1/accuracy²（缺精度按 [DEFAULT_ACCURACY_METERS]）—— 精度好的点说话算数，
     * 室内几百米的点几乎不参与。窗口同时受时间（±[SMOOTH_HALF_WINDOW_MS]）与点数
     * （±[SMOOTH_HALF_WINDOW_POINTS]）约束：时间保证「窗口里的点确实在同一位置附近」，
     * 点数保证实时补采时不会一次平均掉半分钟的路。
     *
     * 三条不变量（下游 dedupe / simplify / cap 全靠它们）：
     * - 点数、顺序、时间戳、label、accuracy 一律不动，只改 lat / lng；
     * - 每段首尾坐标不动（段的端点代表真实到达位置，被平均就是凭空缩短轨迹）；
     * - 拐点保护：点偏离「窗口两端连成的弦」超过 max(10m, 2 × 噪声地板) 时保留原坐标
     *   —— 直角拐弯、绕行匝道这类真几何不能被平均掉。
     *
     * 跨 [SMOOTH_MAX_GAP_MS]（5 分钟）以上断档分段，段与段之间互不平滑：把断档两侧的点
     * 平均到一起，会让「10 分钟前在 A、现在在 B」变成一条根本不存在的连线。
     *
     * @param accuracies 时间戳 → 精度（米）；[TraceSample.accuracyM] 优先，其次查这张表
     */
    fun smooth(
        samples: List<TraceSample>,
        accuracies: Map<Long, Float> = emptyMap(),
    ): List<TraceSample> {
        if (samples.size < 3) return samples
        val accuracyOf = { sample: TraceSample ->
            val raw = sample.accuracyM?.toDouble() ?: accuracies[sample.timestamp]?.toDouble()
            if (raw == null || raw.isNaN() || raw < MIN_ACCURACY_METERS) DEFAULT_ACCURACY_METERS else raw
        }
        val out = ArrayList<TraceSample>(samples.size)
        for (segment in segments(samples)) {
            out.add(samples[segment.first])
            val threshold = turnProtectMeters(samples, segment, accuracyOf)
            for (index in segment.first + 1 until segment.last) {
                out.add(smoothAt(samples, segment, index, threshold, accuracyOf))
            }
            if (segment.last > segment.first) out.add(samples[segment.last])
        }
        return out
    }

    /** 按断档把下标切成若干段；时间不倒序（含相等）也算断开，避免窗口跨乱序点取值。 */
    private fun segments(samples: List<TraceSample>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var start = 0
        for (index in 1 until samples.size) {
            val gap = samples[index].timestamp - samples[index - 1].timestamp
            if (gap > SMOOTH_MAX_GAP_MS || gap < 0L) {
                ranges.add(start until index)
                start = index
            }
        }
        ranges.add(start until samples.size)
        return ranges
    }

    /** 段内噪声地板取精度的中位数（抗单个坏点污染），拐点阈值 = max(10m, 2 × 地板)。 */
    private fun turnProtectMeters(
        samples: List<TraceSample>,
        segment: IntRange,
        accuracyOf: (TraceSample) -> Double,
    ): Double {
        val sorted = segment.map { accuracyOf(samples[it]) }.sorted()
        val floor = sorted[sorted.size / 2]
        return max(TURN_PROTECT_MIN_METERS, TURN_PROTECT_NOISE_FACTOR * floor)
    }

    /** 段内第 [index] 个点的平滑结果；窗口与拐点保护都没命中时返回原点。 */
    private fun smoothAt(
        samples: List<TraceSample>,
        segment: IntRange,
        index: Int,
        threshold: Double,
        accuracyOf: (TraceSample) -> Double,
    ): TraceSample {
        val origin = samples[index]
        val from = windowEdge(samples, segment, index, -1)
        val to = windowEdge(samples, segment, index, 1)
        if (from >= to) return origin
        if (TripDenoise.perpendicularMeters(samples[from], samples[to], origin) > threshold) return origin
        var sumWeight = 0.0
        var sumLat = 0.0
        var sumLng = 0.0
        for (j in from..to) {
            val accuracy = accuracyOf(samples[j])
            val weight = 1.0 / (accuracy * accuracy)
            sumWeight += weight
            sumLat += samples[j].lat * weight
            sumLng += samples[j].lng * weight
        }
        if (sumWeight <= 0.0) return origin
        return origin.copy(lat = sumLat / sumWeight, lng = sumLng / sumWeight)
    }

    /** 从 [index] 向 [step] 方向扩窗口，停在段边界、时间半宽或点数半宽上。 */
    private fun windowEdge(
        samples: List<TraceSample>,
        segment: IntRange,
        index: Int,
        step: Int,
    ): Int {
        val anchor = samples[index].timestamp
        var edge = index
        var count = 0
        while (count < SMOOTH_HALF_WINDOW_POINTS) {
            val next = edge + step
            if (next !in segment) break
            if (abs(samples[next].timestamp - anchor) > SMOOTH_HALF_WINDOW_MS) break
            edge = next
            count++
        }
        return edge
    }

    /**
     * 按几何误差抽稀（Douglas–Peucker）。
     *
     * 阈值按轨迹自身尺度自适应：包围盒对角线 / [SIMPLIFY_SCALE_DIVISOR]，夹在
     * [SIMPLIFY_MIN_METERS]..[SIMPLIFY_MAX_METERS]。这样市区几百米的轨迹不会被抹平，
     * 跨城轨迹也不会因为阈值固定而保留一堆无意义的中间点。
     *
     * 首尾一定保留。
     *
     * @param toleranceMeters 显式阈值（米）；<= 0 时用自适应阈值（单测用它钉死数值）
     */
    fun simplify(samples: List<TraceSample>, toleranceMeters: Double = 0.0): List<TraceSample> {
        if (samples.size < 3) return samples
        val tolerance = if (toleranceMeters > 0.0) toleranceMeters else adaptiveTolerance(samples)
        val keep = BooleanArray(samples.size)
        keep[0] = true
        keep[samples.lastIndex] = true
        simplifyRange(samples, keep, tolerance)
        return samples.filterIndexed { index, _ -> keep[index] }
    }

    /** 自适应阈值：包围盒对角线 / 分母，夹到上下界。 */
    internal fun adaptiveTolerance(samples: List<TraceSample>): Double {
        val diagonal = TripTrace.meters(
            samples.minOf { it.lat },
            samples.minOf { it.lng },
            samples.maxOf { it.lat },
            samples.maxOf { it.lng },
        )
        return (diagonal / SIMPLIFY_SCALE_DIVISOR).coerceIn(SIMPLIFY_MIN_METERS, SIMPLIFY_MAX_METERS)
    }

    /** 区间抽稀；用显式栈而不是递归，避免 500 点极端折线把调用栈打穿。 */
    private fun simplifyRange(samples: List<TraceSample>, keep: BooleanArray, tolerance: Double) {
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to samples.lastIndex)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end - start < 2) continue
            var farthest = -1
            var farthestDistance = 0.0
            for (index in start + 1 until end) {
                val distance = perpendicularMeters(samples[start], samples[end], samples[index])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = index
                }
            }
            if (farthest > start && farthestDistance > tolerance) {
                keep[farthest] = true
                stack.addLast(start to farthest)
                stack.addLast(farthest to end)
            }
        }
    }

    /** 点到线段的垂距（米）；线段退化成一点时退化为点到点距离。 */
    internal fun perpendicularMeters(a: TraceSample, b: TraceSample, point: TraceSample): Double {
        // 等距圆柱：以 a 为原点换到局部平面，经度尺度取三点平均纬度
        val meanLat = (a.lat + b.lat + point.lat) / 3.0
        val metersPerDegLng = METERS_PER_DEGREE * cos(Math.toRadians(meanLat))
        val bx = (b.lng - a.lng) * metersPerDegLng
        val by = (b.lat - a.lat) * METERS_PER_DEGREE
        val px = (point.lng - a.lng) * metersPerDegLng
        val py = (point.lat - a.lat) * METERS_PER_DEGREE
        val lengthSquared = bx * bx + by * by
        if (lengthSquared <= 0.0) return hypot(px, py)
        val t = ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }
}
