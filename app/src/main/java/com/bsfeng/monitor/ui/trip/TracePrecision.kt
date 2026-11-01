package com.bsfeng.monitor.ui.trip

// 轨迹「有多可信」的纯逻辑：把一个采样点的精度折成画布用得上的两档 + 一天是否历史粗坐标。
// 不依赖 Android / Compose，可在纯 JVM 单测里逐条验证（TracePrecisionTest）。
//
// 为什么需要它：轨迹卡原来把整条轨迹画成一条单色折线，5m 的 GPS 点与 400m 的 network 点
// 看起来一样可信 —— 图不表达不确定。历史库里的 3 位小数坐标（≈110m 量化格）同理：
// 画出来是阶梯，但界面从没说过这是量化造成的。

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.record.FixConfidence
import com.bsfeng.monitor.record.LocationQuality
import kotlin.math.abs
import kotlin.math.round

/** 采样点的精度档：决定轨迹线的那一段怎么画。 */
enum class FixBand {
    /** 精度够当轨迹主点（[LocationQuality.HIGH_ACCURACY_METERS] 以内）。 */
    HIGH,

    /** 偏差但仍在容差内：照画，但要让人看得出「这一段不可全信」。 */
    LOW,
}

/**
 * 一条定位采样自带的原始质量字段。
 *
 * 与 [TraceSample] 解耦：采样点只有坐标（T2 的平滑链在用），精度与 provider 在本文件里
 * 按 timestamp 关联，避免为了画线去改平滑链的数据结构。
 */
data class TraceFix(val provider: String?, val accuracyMeters: Float?)

/**
 * 一天的精度画像，供绘制与文案共用同一份判定。
 *
 * @param bands 与采样点**等长**的顺序分档，画线时按段取相邻两点的档位
 * @param coarseShare 坐标小数位 ≤ [TracePrecision.COARSE_DECIMALS] 的采样占比（0..1）
 */
data class TracePrecisionProfile(
    val bands: List<FixBand> = emptyList(),
    val coarseShare: Double = 0.0,
) {
    /** 这一天是否「历史粗坐标」：≥[TracePrecision.COARSE_SHARE_THRESHOLD] 的采样落在 3 位小数网格上。 */
    val isCoarseDay: Boolean get() = coarseShare >= TracePrecision.COARSE_SHARE_THRESHOLD

    /** 低置信采样占比（0..1）；为空时按 0 处理。 */
    val lowConfidenceShare: Double
        get() = if (bands.isEmpty()) 0.0 else bands.count { it == FixBand.LOW }.toDouble() / bands.size

    val hasLowConfidence: Boolean get() = bands.any { it == FixBand.LOW }
}

object TracePrecision {

    /**
     * 历史库坐标的小数位上限：3 位（≈110m 网格）。
     *
     * 0.001° 纬度 ≈111m、经度按 cos(纬度) 压缩（北纬 40° 约 85m），取 110m 作口径。
     */
    const val COARSE_DECIMALS = 3

    /** 判「这一天是历史粗坐标」的采样占比门槛：多数点（≥60%）落在网格上才算，个别点不算。 */
    const val COARSE_SHARE_THRESHOLD = 0.6

    /** 历史粗坐标的网格边长（米），只用于文案与说明，不参与判定。 */
    const val COARSE_RESOLUTION_METERS = 110

    /** 小数位最多探到这个深度：再细在当前坐标精度下没有区分意义。 */
    private const val MAX_PROBED_DECIMALS = 9

    /** 判「是不是整数倍」的容差（按放大后的量级取绝对容差，Double 有效位约 1e-9 相对误差）。 */
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

    /** 经纬度都落在 3 位小数网格上 = 这个点是被量化过的历史坐标。 */
    fun isCoarseCoordinate(lat: Double, lng: Double): Boolean =
        decimalPlaces(lat) <= COARSE_DECIMALS && decimalPlaces(lng) <= COARSE_DECIMALS

    /** 粗坐标采样占比（0..1）；空列表按 0 处理。 */
    fun coarseShare(samples: List<TraceSample>): Double {
        if (samples.isEmpty()) return 0.0
        return samples.count { isCoarseCoordinate(it.lat, it.lng) }.toDouble() / samples.size
    }

    /**
     * 按精度给点分档。
     *
     * 阈值与 provider 口径**只有** [LocationQuality] 一份（见其 KDoc：精度分级只看精度，
     * network 除外）；没给精度（null / ≤0）按 [FixBand.HIGH] —— 缺数据不虚构不确定。
     */
    fun bandOf(fix: TraceFix?): FixBand {
        val accuracy = fix?.accuracyMeters ?: return FixBand.HIGH
        if (accuracy <= 0f) return FixBand.HIGH
        val provider = fix.provider?.takeIf { it.isNotBlank() }
        if (provider == null) {
            return if (accuracy <= LocationQuality.HIGH_ACCURACY_METERS) FixBand.HIGH else FixBand.LOW
        }
        return if (LocationQuality.confidenceOf(provider, accuracy) == FixConfidence.HIGH) {
            FixBand.HIGH
        } else {
            FixBand.LOW
        }
    }

    /**
     * 一天的精度画像。
     *
     * @param fixes timestamp → 该点的 provider / accuracy；缺项按「没给精度」处理
     */
    fun profile(samples: List<TraceSample>, fixes: Map<Long, TraceFix> = emptyMap()): TracePrecisionProfile {
        if (samples.isEmpty()) return TracePrecisionProfile()
        return TracePrecisionProfile(
            bands = samples.map { bandOf(fixes[it.timestamp]) },
            coarseShare = coarseShare(samples),
        )
    }

    /**
     * 从 LOCATION 记录里取 provider / accuracy；不可解析的字段留 null。
     *
     * 与 [TripTrace] 的解析同口径（同一个 detail 键名），但**不重复**它的离群判定：
     * 这里只回答「这个点系统当时说它多准」。
     */
    fun fixesOf(records: List<Record>): Map<Long, TraceFix> = buildMap {
        records.forEach { record ->
            val detail = record.detail ?: return@forEach
            val accuracy = detail["accuracy_m"]?.toFloatOrNull()
            val provider = detail["provider"]?.takeIf { it.isNotBlank() }
            if (accuracy == null && provider == null) return@forEach
            put(record.timestamp, TraceFix(provider, accuracy))
        }
    }
}
