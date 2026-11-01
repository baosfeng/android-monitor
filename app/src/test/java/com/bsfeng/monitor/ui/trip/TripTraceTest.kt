package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * 每日轨迹投影（TripTrace）单元测试。
 *
 * 覆盖：空点 / 单点 / 两点 / 全部重合 / 跨经度 / 去重 / 抽稀上限 / 画布边界 / 停留锚点与标签。
 * 这些是纯计算，出错只会在真机上表现为「轨迹画歪 / 画到画布外 / 全是 NaN」，故用单测钉住。
 */
class TripTraceTest {

    private fun location(ts: Long, lat: Double, lng: Double, address: String? = null): Record = Record(
        id = "loc-$ts",
        type = RecordType.LOCATION,
        action = "LOCATION",
        timestamp = ts,
        label = address ?: "$lat,$lng",
        detail = buildMap {
            put("lat", lat.toString())
            put("lng", lng.toString())
            if (address != null) put("address", address)
        },
    )

    private fun stay(start: Long, end: Long, label: String, lat: Double?, lng: Double?): Stay = Stay(
        start = start,
        end = end,
        place = Place(id = label, label = label, lat = lat, lng = lng, source = Basis.GPS),
    )

    @Test
    fun `空记录得到空轨迹与空投影`() {
        assertTrue(TripTrace.samples(emptyList()).isEmpty())
        val projection = TripTrace.project(emptyList(), emptyList(), 300f, 180f, 16f)
        assertTrue(projection.points.isEmpty())
        assertFalse(projection.hasTrace)
    }

    @Test
    fun `单点居中`() {
        val samples = TripTrace.samples(listOf(location(1_000, 31.23, 121.47)))
        assertEquals(1, samples.size)
        val projection = TripTrace.project(samples, emptyList(), 300f, 180f, 16f)
        assertEquals(1, projection.points.size)
        assertEquals(150f, projection.points[0].x, 0.01f)
        assertEquals(90f, projection.points[0].y, 0.01f)
    }

    @Test
    fun `两点按经度方向从左到右`() {
        val samples = TripTrace.samples(
            listOf(location(1_000, 31.23, 121.40), location(2_000, 31.23, 121.50)),
        )
        assertEquals(2, samples.size)
        val p = TripTrace.project(samples, emptyList(), 300f, 180f, 16f)
        assertTrue(p.points[0].x < p.points[1].x)
        assertTrue(p.points.all { it.x in 0f..300f && it.y in 0f..180f })
    }

    @Test
    fun `全部重合时居中且不产生 NaN`() {
        val samples = TripTrace.samples(
            listOf(location(1_000, 31.23, 121.47), location(2_000, 31.23, 121.47)),
        )
        // 相邻 <5m 被去重后只剩一个点；这里再直接验证未去重的重合输入
        val coincident = listOf(
            TraceSample(1_000, 31.23, 121.47),
            TraceSample(2_000, 31.23, 121.47),
        )
        val p = TripTrace.project(coincident, emptyList(), 300f, 180f, 16f)
        assertEquals(2, p.points.size)
        p.points.forEach {
            assertEquals(150f, it.x, 0.01f)
            assertEquals(90f, it.y, 0.01f)
            assertFalse(it.x.isNaN() || it.y.isNaN())
        }
        assertEquals(1, samples.size)
    }

    @Test
    fun `跨经度的两点坐标有限且落在画布内`() {
        val samples = listOf(
            TraceSample(1_000, 10.0, 179.9),
            TraceSample(2_000, 10.0, -179.9),
        )
        val p = TripTrace.project(samples, emptyList(), 320f, 200f, 12f)
        assertEquals(2, p.points.size)
        p.points.forEach {
            assertFalse(it.x.isNaN() || it.y.isNaN())
            assertTrue(it.x in 0f..320f && it.y in 0f..200f)
        }
    }

    @Test
    fun `相邻小于五米的点被去重`() {
        // 0.00002 度纬度 ≈ 2.2m
        val samples = TripTrace.samples(
            listOf(
                location(1_000, 31.23000, 121.47000),
                location(2_000, 31.23002, 121.47000),
                location(3_000, 31.23100, 121.47000), // ≈ 111m
            ),
        )
        assertEquals(2, samples.size)
        assertEquals(1_000L, samples[0].timestamp)
        assertEquals(3_000L, samples[1].timestamp)
    }

    @Test
    fun `超过五百点按上限抽稀并保留首尾`() {
        val records = (0 until 1_200).map { location(it * 1_000L, 30.0 + it * 0.0001, 120.0 + it * 0.0001) }
        val samples = TripTrace.samples(records)
        assertTrue(samples.size <= TripTrace.MAX_POINTS)
        assertEquals(0L, samples.first().timestamp)
        assertEquals(1_199_000L, samples.last().timestamp)
        assertEquals(samples.sortedBy { it.timestamp }, samples)
    }

    @Test
    fun `画布尺寸非法时返回空投影`() {
        val samples = listOf(TraceSample(1_000, 31.23, 121.47), TraceSample(2_000, 31.24, 121.48))
        assertTrue(TripTrace.project(samples, emptyList(), 0f, 180f, 16f).points.isEmpty())
        assertTrue(TripTrace.project(samples, emptyList(), 300f, -1f, 16f).points.isEmpty())
    }

    @Test
    fun `留白大于画布一半时仍居中且不越界`() {
        val samples = listOf(TraceSample(1_000, 31.23, 121.47), TraceSample(2_000, 31.24, 121.48))
        val p = TripTrace.project(samples, emptyList(), 100f, 100f, 80f)
        assertEquals(2, p.points.size)
        p.points.forEach {
            assertTrue(it.x in 0f..100f && it.y in 0f..100f)
            assertFalse(it.x.isNaN() || it.y.isNaN())
        }
    }

    @Test
    fun `停留锚点只取有坐标的停留且半径在六到十四之间`() {
        val samples = listOf(TraceSample(1_000, 31.23, 121.47), TraceSample(2_000, 31.25, 121.49))
        val stays = listOf(
            stay(1_000, 3_600_000, "公司", 31.23, 121.47),
            stay(3_700_000, 3_800_000, "路上", null, null),
            stay(4_000_000, 7_000_000, "家", 31.25, 121.49),
        )
        val p = TripTrace.project(samples, stays, 300f, 180f, 16f)
        assertEquals(2, p.stays.size)
        p.stays.forEach {
            assertTrue(it.radiusDp >= TripTrace.MIN_STAY_RADIUS_DP - 0.001f)
            assertTrue(it.radiusDp <= TripTrace.MAX_STAY_RADIUS_DP + 0.001f)
        }
        assertTrue(p.stays.first().radiusDp > p.stays.last().radiusDp) // 长时间停留半径更大
    }

    @Test
    fun `停留标签最多六个`() {
        val samples = (0 until 20).map { TraceSample(it.toLong(), 30.0 + it * 0.002, 120.0 + it * 0.002) }
        val stays = (0 until 10).map { i ->
            stay(i * 100_000L, i * 100_000L + 60_000L, "点$i", 30.0 + i * 0.004, 120.0 + i * 0.004)
        }
        val p = TripTrace.project(samples, stays, 300f, 180f, 16f, density = 1f)
        assertTrue(p.stays.count { it.labelVisible } <= TripTrace.MAX_STAY_LABELS)
    }

    @Test
    fun `地址前缀被裁掉且保留可读部分`() {
        assertEquals("文三路 100 号", TripTrace.shortLabel("浙江省杭州市西湖区文三路 100 号"))
        assertEquals("家", TripTrace.shortLabel("家"))
        assertEquals("", TripTrace.shortLabel(""))
        assertTrue(TripTrace.shortLabel("浙江省杭州市西湖区文三路 100 号").length <= 12)
    }

    // ==================== 最小可视跨度（原地不动的一天） ====================

    private fun latAt(meters: Double) = 31.2176 + meters / 111_320.0

    private fun lngAt(meters: Double) = 121.2579 + meters / (111_320.0 * cos(Math.toRadians(31.2176)))

    @Test
    fun `二十五米的抖动只占画布八分之一以内`() {
        // 一天 25 个点全在同一栋楼，最远点相距 25m
        val samples = (0 until 9).map { index ->
            val step = 25.0 * index / 8.0
            TraceSample(index * 60_000L, latAt(step), lngAt(step))
        }
        val p = TripTrace.project(samples, emptyList(), 300f, 180f, 16f)
        assertEquals(9, p.points.size)
        val spanX = p.points.maxOf { it.x } - p.points.minOf { it.x }
        val spanY = p.points.maxOf { it.y } - p.points.minOf { it.y }
        assertTrue("x 跨度 ${spanX}px 应 ≤ 300/8", spanX <= 300f / 8f)
        assertTrue("y 跨度 ${spanY}px 应 ≤ 180/8", spanY <= 180f / 8f)
        p.points.forEach { assertTrue(it.x in 0f..300f && it.y in 0f..180f) }
    }

    @Test
    fun `两公里的真实出行仍撑满画布`() {
        val samples = (0 until 21).map { index ->
            TraceSample(index * 60_000L, latAt(index * 100.0), 121.2579)
        }
        val p = TripTrace.project(samples, emptyList(), 300f, 180f, 16f)
        val spanY = p.points.maxOf { it.y } - p.points.minOf { it.y }
        val inner = 180f - 2f * 16f
        assertEquals("纵向应撑满留白内的画布", inner.toDouble(), spanY.toDouble(), inner.toDouble() * 0.05)
    }

    @Test
    fun `非定位记录不进入轨迹`() {
        val other = Record(
            id = "wifi-1",
            type = RecordType.WIFI,
            action = "CONNECTED",
            timestamp = 1_000,
            label = "WiFi：公司",
            detail = mapOf("ssid" to "公司"),
        )
        assertTrue(TripTrace.samples(listOf(other)).isEmpty())
    }
}
