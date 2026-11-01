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

    @Test
    fun `静止抖动平滑后到真值的平均偏差至少降一半`() {
        val truthLat = 31.23
        val truthLng = 121.47
        val raw = stationaryJitter(30.0, 60, 30f)
        val smoothed = TripDenoise.smooth(raw)
        assertEquals("点数必须一模一样", raw.size, smoothed.size)
        val before = meanDeviation(raw, truthLat, truthLng)
        val after = meanDeviation(smoothed, truthLat, truthLng)
        println("静止抖动 60 点(±30m)：改前平均偏差 " + before + " m → 改后 " + after +
            " m（降 " + (100.0 * (before - after) / before) + "%）")
        assertTrue("平滑后平均偏差必须下降一半以上（改前 " + before + " → " + after + "）", after <= before * 0.5)
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
        // 只有第 3 个点向北偏 20m，其余 6 个点都在真值上：五点等权平均后应偏移 4m
        val truthLat = 31.0
        val truthLng = 121.0
        val raw = (0 until 7).map { index ->
            val offset = if (index == 3) 20.0 else 0.0
            TraceSample(
                timestamp = index * 10_000L,
                lat = truthLat + latOffset(offset),
                lng = truthLng,
                accuracyM = 20f,
            )
        }
        val smoothed = TripDenoise.smooth(raw)
        assertEquals("每段首点不动", raw[0], smoothed[0])
        assertEquals("每段末点不动", raw[6], smoothed[6])
        assertEquals("五点平均：20m 的偏移被摊成 4m", 4.0, deviation(smoothed[3], truthLat, truthLng), 0.01)
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
