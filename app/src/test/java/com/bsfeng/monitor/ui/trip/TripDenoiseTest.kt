package com.bsfeng.monitor.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹清洗（TripDenoise）单元测试。
 *
 * 这些断言对应「定位过程中画的地图不准」的三种真实现象：
 * 漂移点把折线拽出尖角、抽稀抹掉拐点、以及没有精度信息时被误删的真实移动。
 */
class TripDenoiseTest {

    private fun sample(ts: Long, lat: Double, lng: Double) = TraceSample(ts, lat, lng)

    /** 纬度每 0.001 度约 111.32 米。 */
    private fun latOffset(meters: Double) = meters / 111_320.0

    @Test
    fun `空集合与单点原样返回`() {
        assertTrue(TripDenoise.rejectOutliers(emptyList()).isEmpty())
        assertEquals(1, TripDenoise.rejectOutliers(listOf(sample(0, 31.0, 121.0))).size)
        assertTrue(TripDenoise.simplify(emptyList()).isEmpty())
        assertEquals(1, TripDenoise.simplify(listOf(sample(0, 31.0, 121.0))).size)
    }

    @Test
    fun `没有精度信息时不剔除任何点`() {
        val jump = listOf(
            sample(0, 31.0, 121.0),
            sample(10_000, 31.5, 121.5),
            sample(20_000, 31.5001, 121.5001),
        )
        assertEquals(3, TripDenoise.rejectOutliers(jump).size)
    }

    @Test
    fun `十秒跳出数公里的漂移点被剔除`() {
        val samples = listOf(
            sample(0, 31.2300, 121.4700),
            sample(10_000, 31.2301, 121.4701),
            sample(20_000, 31.2570, 121.4701),
            sample(30_000, 31.2303, 121.4703),
        )
        val accuracies = mapOf(0L to 20f, 10_000L to 20f, 20_000L to 400f, 30_000L to 20f)
        val kept = TripDenoise.rejectOutliers(samples, accuracies)
        assertEquals(3, kept.size)
        assertFalse("漂移点必须被丢掉", kept.any { it.timestamp == 20_000L })
    }

    @Test
    fun `真实步行速度内的点一个都不删`() {
        val samples = (0 until 6).map { index ->
            sample(index * 10_000L, 31.23 + latOffset(index * 15.0), 121.47)
        }
        val accuracies = samples.associate { it.timestamp to 12f }
        assertEquals(6, TripDenoise.rejectOutliers(samples, accuracies).size)
    }

    @Test
    fun `精度很差时允许更大的位移`() {
        // 10 秒走 800m 属于 288km/h，超出任何地面交通：精度 20m 时判为跳变
        val jump = listOf(sample(0, 31.0, 121.0), sample(10_000, 31.0 + latOffset(800.0), 121.0))
        assertFalse(TripDenoise.isPlausible(jump[0], jump[1], mapOf(0L to 20f, 10_000L to 20f)))
        // 同样的位移，两端精度 400m 时误差圈就有 1.6km，「跳到哪」本就不可信，不该删
        assertTrue(TripDenoise.isPlausible(jump[0], jump[1], mapOf(0L to 400f, 10_000L to 400f)))
    }

    @Test
    fun `连续跳变时逐个剔除而不是断链`() {
        val samples = listOf(
            sample(0, 31.0, 121.0),
            sample(10_000, 31.05, 121.0),
            sample(20_000, 31.10, 121.0),
            sample(30_000, 31.0001, 121.0),
        )
        val accuracies = samples.associate { it.timestamp to 15f }
        val kept = TripDenoise.rejectOutliers(samples, accuracies)
        assertEquals(2, kept.size)
        assertEquals(0L, kept.first().timestamp)
        assertEquals(30_000L, kept.last().timestamp)
    }

    @Test
    fun `时间差为零时不会除零也不会误删`() {
        val a = sample(5_000, 31.0, 121.0)
        val b = sample(5_000, 31.0 + latOffset(10.0), 121.0)
        assertTrue(TripDenoise.isPlausible(a, b, mapOf(5_000L to 10f)))
    }

    @Test
    fun `直线上的中间点被抽稀掉而拐点保留`() {
        val straight = (0..10).map { index -> sample(index * 1_000L, 31.0 + latOffset(index * 30.0), 121.0) }
        assertEquals("一条直线上只该剩首尾", 2, TripDenoise.simplify(straight, 5.0).size)

        // 先向北 334m（第 1 点处拐弯），再向东 572m：拐点在 ts=1_000
        val bent = listOf(
            sample(0, 31.0000, 121.0000),
            sample(1_000, 31.0030, 121.0000),
            sample(2_000, 31.0030, 121.0030),
            sample(3_000, 31.0030, 121.0060),
        )
        val kept = TripDenoise.simplify(bent, 5.0)
        assertTrue("拐点不能被抹掉", kept.any { it.timestamp == 1_000L })
        // ts=2_000 与 1_000→3_000 的线段共线（同在 31.0030 纬线上），本就该被抽掉
        assertFalse("共线中间点应被抽掉", kept.any { it.timestamp == 2_000L })
        assertEquals(3, kept.size)
    }

    @Test
    fun `抽稀阈值随轨迹尺度自适应且被夹住`() {
        val tiny = (0..5).map { index -> sample(index.toLong(), 31.0 + latOffset(index * 2.0), 121.0) }
        val huge = (0..5).map { index -> sample(index.toLong(), 31.0 + latOffset(index * 20_000.0), 121.0) }
        assertTrue(TripDenoise.adaptiveTolerance(tiny) >= 1.5)
        assertTrue(TripDenoise.adaptiveTolerance(huge) <= 40.0)
        assertTrue(TripDenoise.adaptiveTolerance(huge) >= TripDenoise.adaptiveTolerance(tiny))
    }

    @Test
    fun `抽稀一定保留首尾且保持升序`() {
        val samples = (0 until 40).map { index ->
            sample(index.toLong(), 31.0 + latOffset(index * 25.0), 121.0 + latOffset(index * 10.0))
        }
        val simplified = TripDenoise.simplify(samples)
        assertEquals(0L, simplified.first().timestamp)
        assertEquals(39L, simplified.last().timestamp)
        assertEquals(simplified.sortedBy { it.timestamp }, simplified)
    }

    @Test
    fun `垂距计算对退化成点的线段返回点距`() {
        val a = sample(0, 31.0, 121.0)
        val point = sample(1, 31.0 + latOffset(100.0), 121.0)
        assertEquals(100.0, TripDenoise.perpendicularMeters(a, a, point), 1.0)
    }

    @Test
    fun `垂距对垂直偏移给出正确米数`() {
        val a = sample(0, 31.0, 121.0)
        val b = sample(1, 31.0 + latOffset(1000.0), 121.0)
        val lngOffset = 50.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(31.0)))
        val off = sample(2, 31.0 + latOffset(500.0), 121.0 + lngOffset)
        assertEquals(50.0, TripDenoise.perpendicularMeters(a, b, off), 3.0)
    }

    @Test
    fun `清洗后点数不会反而变多`() {
        val samples = (0 until 60).map { index ->
            sample(index.toLong(), 31.0 + latOffset(index * 12.0), 121.0 + latOffset(index * 8.0))
        }
        val accuracies = samples.associate { it.timestamp to 10f }
        val cleaned = TripDenoise.simplify(TripDenoise.rejectOutliers(samples, accuracies))
        assertTrue(cleaned.size <= samples.size)
        assertTrue(cleaned.isNotEmpty())
    }

    // ==================== 单点漂移（跳出去又跳回） ====================

    /** 31.2176 / 121.2579（同一栋楼），纬度偏移按米换算。 */
    private fun latAt(meters: Double) = BASE_LAT + meters / 111_320.0

    private val baseLng = 121.2579

    /** 19 个正常点：±25m 抖动；与漂移点相邻的 6 个点取 0 偏移，保证跳出/跳回都 > 80m。 */
    private val jitter = listOf(
        0.0, 25.0, -25.0, 18.0, 0.0, 0.0, -12.0, 20.0, -20.0, 15.0,
        0.0, 0.0, 8.0, -8.0, 22.0, -22.0, 0.0, 0.0, 10.0,
    )

    @Test
    fun `原地一天的三个南跳漂移点全部剔除且跨度收敛`() {
        val samples = ArrayList<TraceSample>()
        var ts = 0L
        for (index in jitter.indices) {
            samples.add(sample(ts, latAt(jitter[index]), baseLng))
            ts += 60_000L
            // 03:20 / 04:20 / 06:50 的三次南跳：90m 外一个点，下一拍又跳回
            if (index in setOf(4, 10, 16)) {
                samples.add(sample(ts, latAt(-90.0), baseLng))
                ts += 60_000L
            }
        }
        assertEquals("19 个正常点 + 3 个漂移点", 22, samples.size)

        val kept = TripDenoise.dropDriftSpikes(samples)
        assertEquals(19, kept.size)
        assertFalse(
            "漂移点必须一个不剩（按距基准 > 80m 判定，正常点抖动只有 ±25m）",
            kept.any { TripTrace.meters(it.lat, baseLng, BASE_LAT, baseLng) > 80.0 },
        )
        val span = TripTrace.meters(kept.minOf { it.lat }, baseLng, kept.maxOf { it.lat }, baseLng)
        assertTrue("剔除后跨度应 ≤ 60m，实际 ${span}m", span <= 60.0)
    }

    @Test
    fun `真实步行每点五十米一个都不剔除`() {
        val walk = (0 until 12).map { index -> sample(index * 30_000L, latAt(index * 50.0), baseLng) }
        assertEquals(12, TripDenoise.dropDriftSpikes(walk).size)
    }

    @Test
    fun `首尾不判且连续两个同址漂移点不连坐`() {
        // 首点自己就「在外面」：没有前邻居，必须保留
        val head = listOf(
            sample(0L, latAt(-90.0), baseLng),
            sample(60_000L, latAt(0.0), baseLng),
            sample(120_000L, latAt(5.0), baseLng),
        )
        assertEquals(3, TripDenoise.dropDriftSpikes(head).size)

        // 连续两个同址假点：彼此距离 0，第二条件不成立，两者都保留（那是真的挪了位置）
        val twin = listOf(
            sample(0L, latAt(0.0), baseLng),
            sample(60_000L, latAt(-90.0), baseLng),
            sample(120_000L, latAt(-90.0), baseLng),
            sample(180_000L, latAt(5.0), baseLng),
        )
        val kept = TripDenoise.dropDriftSpikes(twin)
        assertEquals(4, kept.size)
        assertEquals(2, kept.count { TripTrace.meters(it.lat, baseLng, BASE_LAT, baseLng) > 80.0 })
    }
}

/** 真机同址基准纬度（31.2176°N）。 */
private const val BASE_LAT: Double = 31.2176
