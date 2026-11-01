package com.bsfeng.monitor.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹平滑（TripDenoise.smooth）单元测试。
 *
 * 钉的是「静止漂移的锯齿」这一类真实观感问题：原始点围着真值乱跳，Douglas–Peucker 只会
 * 保留极值顶点，锯齿原样画在屏幕上；平滑负责把噪声压回去，同时不许抹掉真实拐弯、
 * 不许跨越断档把两条腿接成一条假线、也不许改动时间戳 / 顺序 / 标签 / 点数。
 *
 * 数值全部由固定种子的伪随机序列生成，改测试数据即视为改验收标准。
 */
class TripSmoothTest {

    private fun latOffset(meters: Double) = meters / 111_320.0

    private fun lngOffset(meters: Double, atLat: Double) =
        meters / (111_320.0 * kotlin.math.cos(Math.toRadians(atLat)))

    /** 固定种子的线性同余发生器：同一条序列每次都一样，报数可复现。 */
    private class Jitter(seed: Long) {
        private var state = seed
        fun unit(): Double {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val normalized = ((state ushr 11).toDouble()) / (1L shl 53).toDouble()
            return normalized * 2.0 - 1.0
        }
    }

    private fun deviation(sample: TraceSample, lat: Double, lng: Double) =
        TripTrace.meters(lat, lng, sample.lat, sample.lng)

    private fun meanDeviation(samples: List<TraceSample>, lat: Double, lng: Double) =
        samples.sumOf { deviation(it, lat, lng) } / samples.size

    /** 静止：真值固定，每 10 秒一个点，坐标在 ±[meters] 内均匀抖动。 */
    private fun stationaryJitter(meters: Double, count: Int, accuracy: Float?): List<TraceSample> {
        val random = Jitter(20_261_101L)
        val lat = 31.23
        val lng = 121.47
        return (0 until count).map { index ->
            TraceSample(
                timestamp = index * 10_000L,
                lat = lat + latOffset(random.unit() * meters),
                lng = lng + lngOffset(random.unit() * meters, lat),
                accuracyM = accuracy,
            )
        }
    }

    /**
     * 上一版（固定窄窗 ±2 点 / ±60s、单遍）的对照实现：逐行照抄 v1 的 TripDenoise.smooth。
     * 只用于钉住「移动段与 v1 逐点一致」，别在这里加优化。
     */
    private fun narrowWindowSmooth(samples: List<TraceSample>): List<TraceSample> {
        if (samples.size < 3) return samples
        fun accuracyOf(sample: TraceSample): Double {
            val raw = sample.accuracyM?.toDouble()
            return if (raw == null || raw.isNaN() || raw < 1.0) 50.0 else raw
        }
        val segments = ArrayList<IntRange>()
        var start = 0
        for (index in 1 until samples.size) {
            val gap = samples[index].timestamp - samples[index - 1].timestamp
            if (gap > 300_000L || gap < 0L) {
                segments.add(start until index)
                start = index
            }
        }
        segments.add(start until samples.size)
        val out = ArrayList<TraceSample>(samples.size)
        for (segment in segments) {
            out.add(samples[segment.first])
            val floors = segment.map { accuracyOf(samples[it]) }.sorted()
            val threshold = maxOf(10.0, 2.0 * floors[floors.size / 2])
            for (index in segment.first + 1 until segment.last) {
                val origin = samples[index]
                var from = index
                var to = index
                while (index - from < 2 && from > segment.first &&
                    kotlin.math.abs(samples[from - 1].timestamp - origin.timestamp) <= 60_000L
                ) from--
                while (to - index < 2 && to < segment.last &&
                    kotlin.math.abs(samples[to + 1].timestamp - origin.timestamp) <= 60_000L
                ) to++
                if (from < to && TripDenoise.perpendicularMeters(samples[from], samples[to], origin) > threshold) {
                    out.add(origin)
                    continue
                }
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
                out.add(if (sumWeight <= 0.0) origin else origin.copy(lat = sumLat / sumWeight, lng = sumLng / sumWeight))
            }
            if (segment.last > segment.first) out.add(samples[segment.last])
        }
        return out
    }

    @Test
    fun `静止抖动平滑后到真值的平均偏差不超过五米`() {
        val truthLat = 31.23
        val truthLng = 121.47
        val raw = stationaryJitter(30.0, 60, 30f)
        val smoothed = TripDenoise.smooth(raw)
        assertEquals("点数必须一模一样", raw.size, smoothed.size)
        val before = meanDeviation(raw, truthLat, truthLng)
        val after = meanDeviation(smoothed, truthLat, truthLng)
        println("静止抖动 60 点(±30m)：改前平均偏差 " + before + " m → 改后 " + after +
            " m（降 " + (100.0 * (before - after) / before) + "%）")
        assertTrue("静止抖动必须压到 5m 以内（改前 " + before + " → " + after + "）", after <= 5.0)
    }

    @Test
    fun `直角拐弯的拐点位移不超过十米`() {
        // 向北 200m 后向东 200m：拐点在 ts=10_000，几何上偏离弦 200/√2 ≈ 141m
        val base = 31.0 to 121.0
        val treatLat = base.first + latOffset(200.0)
        val corner = TraceSample(10_000L, treatLat, base.second, accuracyM = 10f)
        val raw = listOf(
            TraceSample(0L, base.first, base.second, accuracyM = 10f),
            TraceSample(5_000L, base.first + latOffset(100.0), base.second, accuracyM = 10f),
            corner,
            TraceSample(15_000L, treatLat, base.second + lngOffset(100.0, treatLat), accuracyM = 10f),
            TraceSample(20_000L, treatLat, base.second + lngOffset(200.0, treatLat), accuracyM = 10f),
        )
        val smoothed = TripDenoise.smooth(raw)
        assertEquals(raw.size, smoothed.size)
        val moved = deviation(smoothed[2], corner.lat, corner.lng)
        println("直角拐弯：拐点位移 " + moved + " m")
        assertTrue("拐点位移必须 ≤10m（实际 " + moved + " m）", moved <= 10.0)
    }

    @Test
    fun `拐点保护阈值随精度放大-低精度拐弯允许被抹平`() {
        // 同一个 200m 直角：精度 100m 时噪声地板就是 100m，141m 的偏离不再算「真几何」
        val corners = listOf(
            TraceSample(0L, 31.0, 121.0, accuracyM = 100f),
            TraceSample(10_000L, 31.0 + latOffset(200.0), 121.0, accuracyM = 100f),
            TraceSample(20_000L, 31.0 + latOffset(200.0), 121.0 + lngOffset(200.0, 31.0), accuracyM = 100f),
        )
        val moved = deviation(TripDenoise.smooth(corners)[1], corners[1].lat, corners[1].lng)
        assertTrue("低精度点的拐弯本就不可信，允许被平均（实际位移 " + moved + " m）", moved > 10.0)
    }

    @Test
    fun `断档两侧的点不被拉近`() {
        val nearLat = 31.0
        val nearLng = 121.0
        val farLat = 31.08
        val farLng = 121.08
        val random = Jitter(7L)
        val firstLeg = (0 until 5).map { index ->
            TraceSample(
                timestamp = index * 10_000L,
                lat = nearLat + latOffset(random.unit() * 20.0),
                lng = nearLng + lngOffset(random.unit() * 20.0, nearLat),
                accuracyM = 30f,
            )
        }
        val secondStart = 40_000L + 15 * 60_000L
        val secondLeg = (0 until 5).map { index ->
            TraceSample(
                timestamp = secondStart + index * 10_000L,
                lat = farLat + latOffset(random.unit() * 20.0),
                lng = farLng + lngOffset(random.unit() * 20.0, farLat),
                accuracyM = 30f,
            )
        }
        val raw = firstLeg + secondLeg
        val smoothed = TripDenoise.smooth(raw)
        assertEquals(raw.size, smoothed.size)
        assertEquals("断档前的最后一个点必须原地不动", raw[4], smoothed[4])
        assertEquals("断档后的第一个点必须原地不动", raw[5], smoothed[5])
        val before = TripTrace.meters(raw[4].lat, raw[4].lng, raw[5].lat, raw[5].lng)
        val after = TripTrace.meters(smoothed[4].lat, smoothed[4].lng, smoothed[5].lat, smoothed[5].lng)
        assertEquals("断档两侧的距离不能变", before, after, 1e-6)
    }

    @Test
    fun `窗口是五点加权平均-钉死位移数值`() {
        // 直线向东每 10 秒 100m，只有第 3 个点向北偏 20m：相邻位移 100m 远大于两端精度和(40m)，
        // 判为「在动」走窄窗；20m 又小于拐点保护的 2×精度(40m)，所以会被平均 —— 五点等权得 4m。
        val truthLat = 31.0
        val truthLng = 121.0
        val raw = (0 until 7).map { index ->
            TraceSample(
                timestamp = index * 10_000L,
                lat = truthLat + latOffset(if (index == 3) 20.0 else 0.0),
                lng = truthLng + lngOffset(100.0 * index, truthLat),
                accuracyM = 20f,
            )
        }
        val smoothed = TripDenoise.smooth(raw)
        assertEquals("每段首点不动", raw[0], smoothed[0])
        assertEquals("每段末点不动", raw[6], smoothed[6])
        assertEquals("五点平均：20m 的横向偏移被摊成 4m", 4.0, deviation(smoothed[3], truthLat, raw[3].lng), 0.01)
        assertEquals("沿行进方向的坐标不该被五点平均挪动", raw[3].lng, smoothed[3].lng, 1e-12)
    }

    @Test
    fun `静止时窗口放宽到二十九点加权平均-钉死位移数值`() {
        // 40 个点都在真值上，只有第 20 个点向北偏 26m；±14 点窗口把 26m 摊成 29 分之一
        val truthLat = 31.0
        val truthLng = 121.0
        val raw = (0 until 40).map { index ->
            val offset = if (index == 20) 26.0 else 0.0
            TraceSample(
                timestamp = index * 10_000L,
                lat = truthLat + latOffset(offset),
                lng = truthLng,
                accuracyM = 20f,
            )
        }
        val smoothed = TripDenoise.smooth(raw)
        assertEquals(raw.size, smoothed.size)
        assertEquals("每段首点不动", raw[0], smoothed[0])
        assertEquals("每段末点不动", raw[39], smoothed[39])
        assertEquals("静止窗口 29 点平均：26m 摊成 26/29", 26.0 / 29.0, deviation(smoothed[20], truthLat, truthLng), 0.01)
    }

    @Test
    fun `前半静止后半移动-移动段与窄窗口口径逐点一致而静止段变准`() {
        val truthLat = 31.0
        val truthLng = 121.0
        val random = Jitter(20_261_102L)
        // 前 40 点：几乎没动，精度差（30m），±30m 抖动
        val still = (0 until 40).map { index ->
            TraceSample(
                timestamp = index * 10_000L,
                lat = truthLat + latOffset(random.unit() * 30.0),
                lng = truthLng + lngOffset(random.unit() * 30.0, truthLat),
                accuracyM = 30f,
            )
        }
        // 后 20 点：每 10 秒向东 100m（远大于两端精度和 20m），明确在移动
        val walking = (0 until 20).map { step ->
            val index = 40 + step
            TraceSample(
                timestamp = index * 10_000L,
                lat = truthLat + latOffset(random.unit() * 3.0),
                lng = truthLng + lngOffset(100.0 * step + random.unit() * 3.0, truthLat),
                accuracyM = 10f,
            )
        }
        val raw = still + walking
        val smoothed = TripDenoise.smooth(raw)
        val narrowOnly = narrowWindowSmooth(raw)
        var maxDelta = 0.0
        for (index in 40 until raw.size) {
            maxDelta = maxOf(
                maxDelta,
                TripTrace.meters(smoothed[index].lat, smoothed[index].lng, narrowOnly[index].lat, narrowOnly[index].lng),
            )
        }
        println("混合数据：移动段与窄窗口口径的最大坐标差 " + maxDelta + " m")
        // 转折点及其后每个移动点都等于窄窗口口径 —— 静止窗口没有跨过转折点
        assertTrue("移动段不许被静止窗口改写（实测最大差 " + maxDelta + " m）", maxDelta <= 1e-9)
        val movedDelta = deviation(smoothed[45], raw[45].lat, raw[45].lng)
        assertTrue("移动段的点本来就在动，平滑不该把它拽回起点", movedDelta < 5.0)
        // 离转折点足够远的静止段确实拿到了宽窗收益
        val stillMean = meanDeviation(smoothed.subList(0, 26), truthLat, truthLng)
        val narrowMean = meanDeviation(narrowOnly.subList(0, 26), truthLat, truthLng)
        println("混合数据：静止段前 26 点平均偏差 宽窗 " + stillMean + " m vs 窄窗 " + narrowMean + " m")
        assertTrue("静止段必须比窄窗口径更准（" + stillMean + " vs " + narrowMean + "）", stillMean < narrowMean * 0.8)
    }

    @Test
    fun `相邻超过六十秒的稀疏点不参与平均`() {
        val raw = listOf(
            TraceSample(0L, 31.0, 121.0, accuracyM = 10f),
            TraceSample(90_000L, 31.0 + latOffset(20.0), 121.0, accuracyM = 10f),
            TraceSample(180_000L, 31.0, 121.0, accuracyM = 10f),
        )
        assertEquals("时间窗两侧都超 60s，坐标必须原样", raw, TripDenoise.smooth(raw))
    }

    @Test
    fun `平滑只移动坐标-时间戳顺序标签与精度都不变`() {
        val raw = stationaryJitter(30.0, 12, 25f).mapIndexed { index, sample ->
            sample.copy(label = if (index % 3 == 0) "点" + index else null)
        }
        val smoothed = TripDenoise.smooth(raw)
        assertEquals(raw.map { it.timestamp }, smoothed.map { it.timestamp })
        assertEquals(raw.map { it.label }, smoothed.map { it.label })
        assertEquals(raw.map { it.accuracyM }, smoothed.map { it.accuracyM })
        assertEquals(smoothed.sortedBy { it.timestamp }, smoothed)
        assertTrue("总得有几个点真的被移动过", raw.zip(smoothed).any { it.first.lat != it.second.lat })
    }

    @Test
    fun `单点两点全重合与缺精度都不崩且点数不变`() {
        assertTrue(TripDenoise.smooth(emptyList()).isEmpty())

        val single = listOf(TraceSample(0L, 31.0, 121.0))
        assertEquals(single, TripDenoise.smooth(single))

        val pair = listOf(TraceSample(0L, 31.0, 121.0), TraceSample(10_000L, 31.01, 121.0))
        assertEquals("两点谈不上平滑", pair, TripDenoise.smooth(pair))

        val coincident = (0 until 6).map { TraceSample(it * 10_000L, 31.0, 121.0, accuracyM = 8f) }
        assertEquals("全部重合时坐标不能漂移", coincident, TripDenoise.smooth(coincident))

        val noAccuracy = stationaryJitter(30.0, 6, null)
        val smoothed = TripDenoise.smooth(noAccuracy)
        assertEquals("缺精度照常平滑，点数不变", noAccuracy.size, smoothed.size)
        assertTrue(smoothed.none { it.lat.isNaN() || it.lng.isNaN() })
        assertFalse("缺精度时不该有任何点被跳过", smoothed.any { it.accuracyM != null })
    }

    @Test
    fun `三点成段时首尾不动只动中间点`() {
        val raw = listOf(
            TraceSample(0L, 31.0, 121.0, accuracyM = 20f),
            TraceSample(10_000L, 31.0 + latOffset(12.0), 121.0, accuracyM = 20f),
            TraceSample(20_000L, 31.0, 121.0, accuracyM = 20f),
        )
        val smoothed = TripDenoise.smooth(raw)
        assertEquals(raw[0], smoothed[0])
        assertEquals(raw[2], smoothed[2])
        // 三点等权平均：12m 的偏移被摊成 1/3
        assertEquals("中间点被拉回两点之间", 4.0, deviation(smoothed[1], smoothed[0].lat, smoothed[0].lng), 0.01)
    }
}