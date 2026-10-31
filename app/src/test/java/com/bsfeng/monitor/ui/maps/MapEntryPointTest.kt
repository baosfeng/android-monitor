package com.bsfeng.monitor.ui.maps

import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.trip.TraceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「打开哪个点」的判定回归：三条优先级 + 非法点跳过。
 *
 * 这段逻辑决定用户点「在地图中查看」后看到的是不是他想看的位置，属于纯数据判定，
 * 因此全部在 JVM 上跑，不依赖 Robolectric。
 */
class MapEntryPointTest {

    private fun sample(lat: Double, lng: Double, label: String? = null) =
        TraceSample(timestamp = 0L, lat = lat, lng = lng, label = label)

    private fun stay(lat: Double?, lng: Double?, label: String) = Stay(
        start = 0L,
        end = 1_000L,
        place = Place(id = "p0", label = label, lat = lat, lng = lng, source = Basis.GPS),
    )

    // ── 1 · 正常路径 ──

    @Test
    fun `有采样时取最后一个采样点`() {
        val samples = listOf(sample(39.90, 116.39), sample(39.91, 116.40), sample(39.92, 116.41))
        assertEquals(GeoPoint(39.92, 116.41), MapEntryPoint.pick(samples, emptyList()))
    }

    @Test
    fun `采样优先于停留点，即使停留点更靠后`() {
        val samples = listOf(sample(39.90, 116.39))
        val stays = listOf(stay(31.23, 121.47, "人民广场"))
        assertEquals("应取采样点", GeoPoint(39.90, 116.39), MapEntryPoint.pick(samples, stays))
    }

    @Test
    fun `没有采样时退到第一个带坐标的停留点`() {
        val stays = listOf(stay(31.23, 121.47, "人民广场"), stay(39.90, 116.39, "家"))
        assertEquals(GeoPoint(31.23, 121.47), MapEntryPoint.pick(emptyList(), stays))
    }

    @Test
    fun `名字优先取带名字的采样，其次停留点`() {
        val samples = listOf(sample(39.90, 116.39, "北京市东城区"))
        assertEquals("北京市东城区", MapEntryPoint.label(samples, emptyList()))
        assertEquals("家", MapEntryPoint.label(emptyList(), listOf(stay(39.9, 116.3, "家"))))
    }

    // ── 2 · 边界路径 ──

    @Test
    fun `采样与停留都为空时返回 null，入口不渲染`() {
        assertNull(MapEntryPoint.pick(emptyList(), emptyList()))
        assertNull(MapEntryPoint.label(emptyList(), emptyList()))
    }

    @Test
    fun `停留点没有坐标时跳过，继续找下一个`() {
        // DEVICE 基准的停留没有坐标（Place.lat/lng 恒为 null），必须跳过而不是返回 null
        val stays = listOf(stay(null, null, "无坐标地点"), stay(39.9, 116.3, "有坐标地点"))
        assertEquals(GeoPoint(39.9, 116.3), MapEntryPoint.pick(emptyList(), stays))
        // 名字也跳过无坐标的那个
        assertEquals("有坐标地点", MapEntryPoint.label(emptyList(), stays))
    }

    @Test
    fun `名字为空白的采样不算有名字`() {
        val samples = listOf(sample(39.9, 116.3, "   "), sample(39.9, 116.3, null))
        // 两个采样都没有有效名字 → 回落停留点
        assertEquals("家", MapEntryPoint.label(samples, listOf(stay(39.9, 116.3, "家"))))
    }

    @Test
    fun `名字前后空白被裁掉`() {
        val samples = listOf(sample(39.9, 116.3, "  天安门  "))
        assertEquals("天安门", MapEntryPoint.label(samples, emptyList()))
    }

    // ── 3 · 异常路径 ──

    @Test
    fun `最后一个采样非法时跳到上一个合法采样`() {
        val samples = listOf(
            sample(39.90, 116.39),
            sample(Double.NaN, 116.40),
            sample(91.0, 116.41), // 纬度越界
        )
        assertEquals("应跳过非法点", GeoPoint(39.90, 116.39), MapEntryPoint.pick(samples, emptyList()))
    }

    @Test
    fun `全部采样非法时回落到停留点`() {
        val samples = listOf(sample(Double.NaN, Double.NaN))
        assertEquals(
            GeoPoint(39.9, 116.3),
            MapEntryPoint.pick(samples, listOf(stay(39.9, 116.3, "家"))),
        )
    }

    @Test
    fun `停留点坐标越界时视为无坐标`() {
        val stays = listOf(stay(120.0, 200.0, "越界"), stay(39.9, 116.3, "正常"))
        assertEquals(GeoPoint(39.9, 116.3), MapEntryPoint.pick(emptyList(), stays))
    }

    @Test
    fun `全部非法且没有停留点时返回 null`() {
        assertNull(MapEntryPoint.pick(listOf(sample(Double.NaN, 1.0)), emptyList()))
    }
}
