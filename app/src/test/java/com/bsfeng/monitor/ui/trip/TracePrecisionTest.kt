package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.LocationQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹精度分档（TracePrecision）单元测试。
 *
 * 覆盖：小数位判定 / 粗坐标占比与门槛 / 精度分档（含 network 特例、缺精度）/ 画像组装 /
 * 记录解析；并钉住「阈值只来自 LocationQuality 一份」，防止有人在本文件里硬编码第二个 100。
 */
class TracePrecisionTest {

    private fun sample(ts: Long, lat: Double, lng: Double) = TraceSample(ts, lat, lng)

    private fun location(ts: Long, lat: Double, lng: Double, provider: String?, accuracy: String?): Record = Record(
        id = "loc-$ts",
        type = RecordType.LOCATION,
        action = "COORDINATE",
        timestamp = ts,
        label = "$lat,$lng",
        detail = buildMap {
            put("lat", lat.toString())
            put("lng", lng.toString())
            if (provider != null) put("provider", provider)
            if (accuracy != null) put("accuracy_m", accuracy)
        },
    )

    // ───────────────── 小数位 / 粗坐标 ─────────────────

    @Test
    fun `小数位按有效数字判`() {
        assertEquals(0, TracePrecision.decimalPlaces(39.0))
        assertEquals(3, TracePrecision.decimalPlaces(39.909))
        assertEquals(3, TracePrecision.decimalPlaces(116.397))
        assertEquals(4, TracePrecision.decimalPlaces(39.9087))
        // 库里存的是 "31.230400" 字符串，解析成 Double 后尾零不留痕：4 位就是 4 位（≈11m，不算粗坐标）
        assertEquals(4, TracePrecision.decimalPlaces(31.230400))
        assertEquals(3, TracePrecision.decimalPlaces(31.230))
    }

    @Test
    fun `负坐标与零值同样可判`() {
        assertEquals(3, TracePrecision.decimalPlaces(-33.868))
        assertEquals(4, TracePrecision.decimalPlaces(-33.8688))
        assertEquals(0, TracePrecision.decimalPlaces(0.0))
    }

    @Test
    fun `两位都落在三位网格才算粗坐标`() {
        assertTrue(TracePrecision.isCoarseCoordinate(39.909, 116.397))
        assertFalse("只有纬度量化不算", TracePrecision.isCoarseCoordinate(39.909, 116.3975))
        assertFalse("只有经度量化不算", TracePrecision.isCoarseCoordinate(39.9087, 116.397))
    }

    @Test
    fun `粗坐标占比按采样点算`() {
        val samples = listOf(
            sample(1, 39.909, 116.397),
            sample(2, 39.910, 116.398),
            sample(3, 39.9087, 116.3975),
            sample(4, 39.9088, 116.3976),
        )
        assertEquals(0.5, TracePrecision.coarseShare(samples), 1e-9)
        assertEquals(0.0, TracePrecision.coarseShare(emptyList()), 1e-9)
    }

    @Test
    fun `六成以上才判历史粗坐标`() {
        fun day(coarse: Int, clean: Int) = TracePrecision.profile(
            List(coarse) { sample(it.toLong(), 39.900 + it * 0.001, 116.300) } +
                List(clean) { sample(100L + it, 39.90870 + it * 0.00001, 116.3975) },
        )

        assertFalse("50% 不算", day(5, 5).isCoarseDay)
        assertTrue("60% 算（门槛含边界）", day(6, 4).isCoarseDay)
        assertTrue(day(10, 0).isCoarseDay)
        assertFalse(day(0, 10).isCoarseDay)
    }

    // ───────────────── 精度分档 ─────────────────

    @Test
    fun `分档阈值与 LocationQuality 同源`() {
        val high = LocationQuality.HIGH_ACCURACY_METERS
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix("gps", high)))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix("gps", 1f)))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(TraceFix("gps", high + 0.5f)))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(TraceFix("gps", 800f)))
    }

    @Test
    fun `network 再准也是低置信`() {
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix("gps", 20f)))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(TraceFix("network", 20f)))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(TraceFix("network", 400f)))
    }

    @Test
    fun `缺精度或非法精度一律高置信而不虚构不确定`() {
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(null))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix("gps", null)))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix("gps", 0f)))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix("gps", -1f)))
    }

    @Test
    fun `provider 缺失时只看精度`() {
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix(null, 30f)))
        assertEquals(FixBand.HIGH, TracePrecision.bandOf(TraceFix(" ", 30f)))
        assertEquals(FixBand.LOW, TracePrecision.bandOf(TraceFix(null, 400f)))
    }

    // ───────────────── 画像组装 ─────────────────

    @Test
    fun `空采样得到空画像`() {
        val profile = TracePrecision.profile(emptyList())
        assertTrue(profile.bands.isEmpty())
        assertFalse(profile.hasLowConfidence)
        assertEquals(0.0, profile.lowConfidenceShare, 1e-9)
        assertFalse(profile.isCoarseDay)
    }

    @Test
    fun `分档与采样点等长且按 timestamp 对齐`() {
        val samples = listOf(sample(10, 39.9087, 116.3975), sample(20, 39.9088, 116.3976), sample(30, 39.9089, 116.3977))
        val profile = TracePrecision.profile(
            samples,
            mapOf(10L to TraceFix("gps", 5f), 20L to TraceFix("network", 400f)),
        )
        assertEquals(samples.size, profile.bands.size)
        assertEquals(listOf(FixBand.HIGH, FixBand.LOW, FixBand.HIGH), profile.bands)
        assertEquals(1.0 / 3, profile.lowConfidenceShare, 1e-9)
        assertTrue(profile.hasLowConfidence)
        assertFalse("6 位小数不是粗坐标", profile.isCoarseDay)
    }

    @Test
    fun `整条低置信时占比为一`() {
        val samples = List(4) { sample(it.toLong(), 39.90 + it * 0.01, 116.30) }
        val fixes = samples.associate { it.timestamp to TraceFix("network", 300f) }
        val profile = TracePrecision.profile(samples, fixes)
        assertEquals(1.0, profile.lowConfidenceShare, 1e-9)
        assertTrue(profile.hasLowConfidence)
    }

    // ───────────────── 记录解析 ─────────────────

    @Test
    fun `从记录解析 provider 与精度`() {
        val fixes = TracePrecision.fixesOf(
            listOf(
                location(1, 39.9, 116.3, "gps", "15"),
                location(2, 39.9, 116.3, "network", "400"),
                location(3, 39.9, 116.3, null, null),
                location(4, 39.9, 116.3, "gps", "abc"),
            ),
        )
        assertEquals(TraceFix("gps", 15f), fixes[1L])
        assertEquals(TraceFix("network", 400f), fixes[2L])
        assertFalse("两个字段都没有时不进表", fixes.containsKey(3L))
        assertEquals("精度不可解析但 provider 保留", TraceFix("gps", null), fixes[4L])
    }

    @Test
    fun `不是定位记录的记录被忽略`() {
        val usage = Record("u-1", RecordType.APP_USAGE, "USAGE", 5, null, "微信", mapOf("package_name" to "x"))
        assertTrue(TracePrecision.fixesOf(listOf(usage)).isEmpty())
    }
}
