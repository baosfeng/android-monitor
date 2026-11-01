package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.record.LocationQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹精度分档（TracePrecision）单元测试。
 *
 * 覆盖：小数位判定 / 粗坐标门槛 / 精度分档（含缺精度）/ 画像组装 / 逐段线型合并；
 * 并钉住「阈值只来自 LocationQuality 一份」，防止有人在本文件里硬编码第二个 100。
 */
class TracePrecisionTest {

    private fun sample(ts: Long, lat: Double, lng: Double, accuracyM: Float? = null) =
        TraceSample(ts, lat, lng, accuracyM = accuracyM)

    // ───────────────── 小数位 / 粗坐标 ─────────────────

    @Test
    fun `小数位按有效数字判`() {
        assertEquals(0, TracePrecision.decimalPlaces(39.0))
        assertEquals(3, TracePrecision.decimalPlaces(39.909))
        assertEquals(3, TracePrecision.decimalPlaces(116.397))
        assertEquals(4, TracePrecision.decimalPlaces(39.9087))
        // 库里存的是 "31.230400" 字符串，解析成 Double 后尾零不留痕：4 位就是 4 位（≈11m，不算粗坐标）
        assertEquals(4, TracePrecision.decimalPlaces(31.230400))
        assertEquals(2, TracePrecision.decimalPlaces(31.23))
    }

    @Test
    fun `负坐标与零值同样可判`() {
        assertEquals(3, TracePrecision.decimalPlaces(-33.868))
        assertEquals(4, TracePrecision.decimalPlaces(-33.8688))
        assertEquals(0, TracePrecision.decimalPlaces(0.0))
    }

    @Test
    fun `经纬度都落在三位网格才算粗坐标`() {
        assertTrue(TracePrecision.isCoarseCoordinate(39.909, 116.397))
        assertTrue("尾零不留痕（39.900 解析后只看得出 1 位）也算落在网格上", TracePrecision.isCoarseCoordinate(39.9, 116.3))
        assertFalse("只有纬度量化不算", TracePrecision.isCoarseCoordinate(39.909, 116.3975))
        assertFalse("只有经度量化不算", TracePrecision.isCoarseCoordinate(39.9087, 116.397))
        assertFalse("6 位小数是新采集", TracePrecision.isCoarseCoordinate(39.909001, 116.397001))
    }

    @Test
    fun `粗坐标占比按采样点算`() {
        val samples = listOf(
            sample(1, 39.909, 116.397),
            sample(2, 39.911, 116.398),
            sample(3, 39.9087, 116.3975),
            sample(4, 39.9088, 116.3976),
        )
        assertEquals(0.5, TracePrecision.coarseShare(samples), 1e-9)
        assertEquals(0.0, TracePrecision.coarseShare(emptyList()), 1e-9)
    }

    @Test
    fun `六成以上才判历史粗坐标`() {
        fun day(coarseCount: Int, cleanCount: Int): List<TraceSample> =
            List(coarseCount) { sample(it.toLong(), 39.901 + it * 0.001, 116.301) } +
                List(cleanCount) { sample(1_000L + it, 39.90870 + it * 0.00001, 116.39750) }

        assertFalse("50% 不算", TracePrecision.isCoarseDay(day(5, 5)))
        assertTrue("60% 算（门槛含边界）", TracePrecision.isCoarseDay(day(6, 4)))
        assertTrue(TracePrecision.isCoarseDay(day(10, 0)))
        assertFalse(TracePrecision.isCoarseDay(day(0, 10)))
        assertTrue(TracePrecision.profile(day(6, 4)).isCoarseDay)
    }

    // ───────────────── 精度分档 ─────────────────

    @Test
    fun `分档阈值与 LocationQuality 同源`() {
        val high = LocationQuality.HIGH_ACCURACY_METERS
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(high))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(1f))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(high + 0.5f))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(800f))
    }

    @Test
    fun `缺精度或非法精度一律高置信而不虚构不确定`() {
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(null))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(0f))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(-1f))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(Float.NaN))
    }

    // ───────────────── 画像组装 ─────────────────

    @Test
    fun `空采样得到空画像`() {
        val profile = TracePrecision.profile(emptyList())
        assertTrue(profile.bands.isEmpty())
        assertFalse(profile.hasLowConfidence)
        assertEquals(0.0, profile.lowConfidenceShare, 1e-9)
        assertFalse(profile.isCoarseDay)
        assertTrue(TracePrecision.segmentRuns(emptyList()).isEmpty())
    }

    @Test
    fun `分档与采样点等长且按精度字段`() {
        val samples = listOf(
            sample(10, 39.9087, 116.3975, 5f),
            sample(20, 39.9088, 116.3976, 400f),
            sample(30, 39.9089, 116.3977, null),
        )
        val profile = TracePrecision.profile(samples)
        assertEquals(samples.size, profile.bands.size)
        assertEquals(listOf(FixBand.HIGH, FixBand.LOW, FixBand.HIGH), profile.bands)
        assertEquals(1.0 / 3, profile.lowConfidenceShare, 1e-9)
        assertTrue(profile.hasLowConfidence)
        assertFalse("6 位小数不是粗坐标", profile.isCoarseDay)
    }

    @Test
    fun `整条低置信时占比为一`() {
        val samples = List(4) { sample(it.toLong(), 39.90 + it * 0.01, 116.30, 300f) }
        val profile = TracePrecision.profile(samples)
        assertEquals(1.0, profile.lowConfidenceShare, 1e-9)
        assertTrue(profile.hasLowConfidence)
    }

    // ───────────────── 逐段线型 ─────────────────

    @Test
    fun `任一端低置信即是低置信段`() {
        assertFalse(TracePrecision.isLowSegment(FixBand.HIGH, FixBand.HIGH))
        assertTrue(TracePrecision.isLowSegment(FixBand.HIGH, FixBand.LOW))
        assertTrue(TracePrecision.isLowSegment(FixBand.LOW, FixBand.HIGH))
        assertTrue(TracePrecision.isLowSegment(FixBand.LOW, FixBand.LOW))
    }

    @Test
    fun `同类线型合并成连续区间`() {
        val bands = listOf(FixBand.HIGH, FixBand.HIGH, FixBand.LOW, FixBand.LOW, FixBand.HIGH)
        assertEquals(
            listOf(SegmentRun(0, 1, low = false), SegmentRun(1, 4, low = true)),
            TracePrecision.segmentRuns(bands),
        )
    }

    @Test
    fun `全高置信只有一段实线`() {
        val bands = List(5) { FixBand.HIGH }
        assertEquals(listOf(SegmentRun(0, 4, low = false)), TracePrecision.segmentRuns(bands))
    }

    @Test
    fun `点不足两个没有可画的段`() {
        assertTrue(TracePrecision.segmentRuns(listOf(FixBand.HIGH)).isEmpty())
        assertTrue(TracePrecision.segmentRuns(emptyList()).isEmpty())
    }
}
