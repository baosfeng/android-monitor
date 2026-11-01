package com.bsfeng.monitor.ui.trip

// 轨迹「有多可信」的纯逻辑：把采样点的精度折成画布用得上的两档 + 判一天是否历史粗坐标。
// 不依赖 Android / Compose，可在纯 JVM 单测里逐条验证（TracePrecisionTest）。
//
// 为什么需要它：轨迹卡原来把整条轨迹画成一条单色折线，5m 的 GPS 点与 400m 的 network 点
// 看起来一样可信 —— 图不表达不确定。历史库里的 3 位小数坐标（≈110m 量化格）同理：
// 画出来是阶梯，但界面从没说过这是量化造成的。

import com.bsfeng.monitor.record.LocationQuality
import kotlin.math.abs
import kotlin.math.round

/** 采样点的精度档：决定轨迹线的那一段怎么画。 */
enum class FixBand {
    /** 精度够当轨迹主点（[LocationQuality.HIGH_ACCURACY_METERS] 以内）：实线。 */
    HIGH,

    /** 偏差但仍在容差内：照画，但要让人看得出「这一段不可全信」——虚线。 */
    LOW,
}

/** 一段同类线型的连续区间：覆盖 `points[from..to]`（含两端），一条路径画完。 */
data class SegmentRun(val from: Int, val to: Int, val low: Boolean)

/**
 * 一天的精度画像，供绘制与文案共用同一份判定。
 *
 * @param bands 与采样点**等长**的顺序分档
 * @param coarseShare 落在 3 位小数网格上的采样占比（0..1）
 */
data class TracePrecisionProfile(
    val bands: List<FixBand> = emptyList(),
    val coarseShare: Double = 0.0,
) {
    /** 这一天是否「历史粗坐标」：≥[TracePrecision.COARSE_SHARE_THRESHOLD] 的采样在 3 位小数网格上。 */
    val isCoarseDay: Boolean get() = coarseShare >= TracePrecision.COARSE_SHARE_THRESHOLD

    /** 低置信采样占比（0..1）；为空时按 0 处理。 */
    val lowConfidenceShare: Double
        get() = if (bands.isEmpty()) 0.0 else bands.count { it == FixBand.LOW }.toDouble() / bands.size

    /** 是否有低置信段（决定图例第二行画不画）。 */
    val hasLowConfidence: Boolean get() = bands.any { it == FixBand.LOW }
}

object TracePrecision {

    /** 历史库坐标的小数位数：正好 3 位（≈110m 网格）。 */
    const val COARSE_DECIMALS = 3

    /**
     * 粗坐标网格边长（米），只用于文案口径，不参与判定。
     *
     * 0.001° 纬度 ≈111m；经度按 cos(纬度) 压缩（北纬 40° 约 85m），取 110m 作一个量级说法。
     */
    const val COARSE_RESOLUTION_METERS = 110

    /** 判「这一天是历史粗坐标」的采样占比门槛：多数点（≥60%）落在网格上才算，个别点不算。 */
    const val COARSE_SHARE_THRESHOLD = 0.6

    /** 小数位最多探到这个深度：再细在当前坐标精度下没有区分意义。 */
    private const val MAX_PROBED_DECIMALS = 9

    /** 判「是不是整数倍」的容差（按放大后的量级取绝对容差：Double 相对误差约 1e-9）。 */
    private const val ROUNDING_EPSILON = 1e-6

    /** 一个坐标标量的小数位数；探不到（浮点噪声）时返回 [MAX_PROBED_DECIMALS]。 */
    fun decimalPlaces(value: Double): Int {
        if (value.isNaN() || value.isInfinite()) return MAX_PROBED_DECIMALS
        var factor = 1.0
        for (places in 0..MAX_PROBED_DECIMALS) {
            val scaled = value * factor
            if (abs(scaled - round(scaled)) < ROUNDING_EPSILON) return places
            factor *= 10.0
        }
        return MAX_PROBED_DECIMALS
    }

    /**
     * 这个点是不是落在 3 位小数网格上的历史坐标：经纬度**都不足 4 位小数**。
     *
     * 判的是「在不在网格上」而不是「数出来几位」：库里存的是 `%.3f` 字符串，
     * `39.900` 解析成 Double 后尾零不留痕（只看得出 1 位），按位数判会把这些点漏掉、
     * 让一份 3 位小数数据被算成「不够粗」。因此用「值 × 10³ 是否整数」判网格。
     * 新采集的 6 位小数坐标落在网格上的概率只有千分之一，个别命中不影响 ≥60% 门槛。
     */
    fun isCoarseCoordinate(lat: Double, lng: Double): Boolean =
        decimalPlaces(lat) <= COARSE_DECIMALS && decimalPlaces(lng) <= COARSE_DECIMALS

    /** 粗坐标采样占比（0..1）；空列表按 0 处理。 */
    fun coarseShare(samples: List<TraceSample>): Double {
        if (samples.isEmpty()) return 0.0
        return samples.count { isCoarseCoordinate(it.lat, it.lng) }.toDouble() / samples.size
    }

    /** 这一天是不是历史粗坐标日（粗坐标日不做平滑：平滑会把量化的台阶糊成斜线）。 */
    fun isCoarseDay(samples: List<TraceSample>): Boolean = coarseShare(samples) >= COARSE_SHARE_THRESHOLD

    /**
     * 按精度给点分档。
     *
     * 阈值**只有** [LocationQuality.HIGH_ACCURACY_METERS] 一份：这里不新增常量，也不按 provider
     * 另判 —— 采样点只带 accuracy（[TraceSample.accuracyM]），没有 provider 名。少数
     * 「network 但只有 20m」的点会按高置信画：精度是 68% 置信半径，照它画不虚构。
     * 没给精度（null / ≤0）按 [FixBand.HIGH]：缺数据不虚构不确定。
     */
    fun bandOf(accuracyM: Float?): FixBand {
        val accuracy = accuracyM ?: return FixBand.HIGH
        if (accuracy <= 0f || accuracy.isNaN()) return FixBand.HIGH
        return if (accuracy <= LocationQuality.HIGH_ACCURACY_METERS) FixBand.HIGH else FixBand.LOW
    }

    /** 一天的精度画像；空采样返回空画像（不抛异常）。 */
    fun profile(samples: List<TraceSample>): TracePrecisionProfile {
        if (samples.isEmpty()) return TracePrecisionProfile()
        return TracePrecisionProfile(
            bands = samples.map { bandOf(it.accuracyM) },
            coarseShare = coarseShare(samples),
        )
    }

    /** 第 [index] 段线（points[index] → points[index+1]）是否低置信：任一端低置信就算 —— 宁可多画虚线。 */
    fun isLowSegment(from: FixBand, to: FixBand): Boolean = from == FixBand.LOW || to == FixBand.LOW

    /**
     * 把逐段线型合并成连续区间（同型段并成一条路径，画线时少建 Path）。
     *
     * 返回空列表表示点不足两个，没有可画的段。
     */
    fun segmentRuns(bands: List<FixBand>): List<SegmentRun> {
        if (bands.size < 2) return emptyList()
        val runs = ArrayList<SegmentRun>()
        var start = 0
        var low = isLowSegment(bands[0], bands[1])
        for (index in 1 until bands.lastIndex) {
            val nextLow = isLowSegment(bands[index], bands[index + 1])
            if (nextLow != low) {
                runs.add(SegmentRun(start, index, low))
                start = index
                low = nextLow
            }
        }
        runs.add(SegmentRun(start, bands.lastIndex, low))
        return runs
    }
}
