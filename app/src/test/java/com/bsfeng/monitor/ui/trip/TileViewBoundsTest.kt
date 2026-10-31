package com.bsfeng.monitor.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底图选级的边界（TileMath.view 的分位数包围盒与单点默认级）。
 *
 * 对应「定位过程中画的地图不准」的第三种现象：一两个漂移点把整条轨迹缩成一小团，
 * 或者只有一个点却被顶到最大级别、周边路网全看不见。
 */
class TileViewBoundsTest {

    private fun sample(lat: Double, lng: Double, ts: Long = 0L) = TraceSample(ts, lat, lng)

    /** 纬度每 0.001 度约 111.32 米。 */
    private fun latOffset(meters: Double) = meters / 111_320.0

    @Test
    fun `单点走街区级而不是最大级`() {
        val view = TileMath.view(listOf(sample(31.2300, 121.4700)), 300.0, 150.0, 12.0)
        assertNotNull(view)
        // 视图级别 = tileZoom - RETINA_BOOST；单点走固定 16 级，不会顶到 19
        assertEquals(TileMath.SINGLE_POINT_ZOOM, view!!.tileZoom - TileMath.RETINA_BOOST)
        assertEquals(150.0, TileMath.canvasX(121.4700, view), 1e-6)
        assertEquals(75.0, TileMath.canvasY(31.2300, view), 1e-6)
    }

    @Test
    fun `两点相距五米也走街区级`() {
        val view = TileMath.view(
            listOf(sample(31.2300, 121.4700), sample(31.2300 + latOffset(5.0), 121.4700)),
            300.0,
            150.0,
            12.0,
        )
        assertNotNull(view)
        assertEquals(TileMath.SINGLE_POINT_ZOOM, view!!.tileZoom - TileMath.RETINA_BOOST)
    }

    @Test
    fun `跨度超过街区阈值时按包围盒正常选级`() {
        val samples = listOf(
            sample(31.2300, 121.4700),
            sample(31.2300 + latOffset(600.0), 121.4700),
        )
        val view = TileMath.view(samples, 300.0, 150.0, 12.0)
        assertNotNull(view)
        assertTrue(
            "600 米的跨度应能得到比街区级更粗的级别",
            view!!.tileZoom - TileMath.RETINA_BOOST < TileMath.SINGLE_POINT_ZOOM,
        )
    }

    @Test
    fun `个别漂移点不会把整条轨迹缩成一团`() {
        // 20 个点集中在 300m 范围内（正常市区行程）
        val dense = (0 until 20).map { index ->
            sample(31.2300 + latOffset(index * 15.0), 121.4700 + latOffset(index * 10.0), index.toLong())
        }
        val withOutlier = dense + sample(31.3000, 121.6000, 99L) // 十几公里外的漂移点
        val clean = TileMath.view(dense, 300.0, 150.0, 12.0)!!
        val polluted = TileMath.view(withOutlier, 300.0, 150.0, 12.0)!!
        // 分位数收缩之后，漂移点几乎不影响缩放：级别最多差 1 级
        val gap = kotlin.math.abs(clean.tileZoom - polluted.tileZoom)
        assertTrue("漂移点让级别掉了 " + gap + " 级", gap <= 1)
    }

    @Test
    fun `点数太少时分位数退回最值`() {
        val few = listOf(sample(31.0, 121.0), sample(31.5, 121.5))
        assertEquals(31.0, TileMath.quantileRange(few) { it.lat }.first, 1e-9)
        assertEquals(31.5, TileMath.quantileRange(few) { it.lat }.second, 1e-9)
    }

    @Test
    fun `点数够时极端值被 IQR 围栏排除`() {
        // 99 个点密集在 31.000..31.098，外加一个 31.5 的极端点
        val many = (0 until 99).map { index -> sample(31.0 + index * 0.001, 121.0, index.toLong()) } +
            sample(31.5, 121.0, 99L)
        val range = TileMath.quantileRange(many) { it.lat }
        assertTrue("极端点应被排除，上界不该到 31.5", range.second < 31.5)
        assertTrue("正常点仍要覆盖", range.second >= 31.09)
        assertEquals("下界保持真实最小值", 31.0, range.first, 1e-9)
    }

    @Test
    fun `所有点在同一条纬线上时不做剔除`() {
        val flat = (0 until 30).map { index -> sample(31.0, 121.0 + index * 0.001, index.toLong()) }
        val range = TileMath.quantileRange(flat) { it.lat }
        assertEquals(31.0, range.first, 1e-9)
        assertEquals(31.0, range.second, 1e-9)
    }

    @Test
    fun `跨经线一百八十度的两点仍落在画布内`() {
        val samples = listOf(sample(10.0, 179.95), sample(10.0, -179.95))
        val view = TileMath.view(samples, 320.0, 200.0, 12.0)!!
        val projection = TripTrace.projectOnMap(samples, emptyList(), view, 320f, 200f)
        assertEquals(2, projection.points.size)
        projection.points.forEach { point ->
            assertTrue("x=" + point.x, point.x.isFinite())
            assertTrue("y=" + point.y, point.y.isFinite())
        }
    }
}
