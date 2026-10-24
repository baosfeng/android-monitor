package com.bsfeng.monitor.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免费底图的投影与瓦片编排（TileMath / TileSource / TripTrace.projectOnMap）单元测试。
 *
 * 这些是纯计算，错了只会表现为「底图和轨迹错位 / 瓦片露缝 / 一屏几百个请求」，
 * 真机上不好定位，故用单测钉住。
 */
class TileMathTest {

    private fun sample(lat: Double, lng: Double, ts: Long = 0L) = TraceSample(ts, lat, lng)

    private val shanghai = listOf(
        sample(31.2300, 121.4700, 0L),
        sample(31.2400, 121.4800, 1_000L),
    )

    @Test
    fun `经度映射到世界像素的两端与中点`() {
        val size = TileMath.worldSize(10)
        assertEquals(0.0, TileMath.worldX(-180.0, 10), 1e-6)
        assertEquals(size / 2.0, TileMath.worldX(0.0, 10), 1e-6)
        assertEquals(size, TileMath.worldX(180.0, 10), 1e-6)
    }

    @Test
    fun `纬度映射在赤道取中点且南北对称`() {
        val size = TileMath.worldSize(10)
        assertEquals(size / 2.0, TileMath.worldY(0.0, 10), 1e-6)
        val north = TileMath.worldY(40.0, 10)
        val south = TileMath.worldY(-40.0, 10)
        assertTrue(north < size / 2.0)
        assertTrue(south > size / 2.0)
        assertEquals(size - south, north, 1e-6)
    }

    @Test
    fun `极区纬度被截断而不是发散成无穷`() {
        for (lat in doubleArrayOf(89.9, -89.9, 90.0, -90.0)) {
            val y = TileMath.worldY(lat, 5)
            assertTrue("lat=" + lat, y.isFinite())
            assertTrue(y >= 0.0 && y <= TileMath.worldSize(5))
        }
    }

    @Test
    fun `选级取能装下轨迹的最大级别`() {
        val city = TileMath.pickZoom(31.2300, 31.2390, 121.4730, 121.4820, 300.0, 150.0)
        val province = TileMath.pickZoom(31.23, 39.90, 121.47, 116.40, 300.0, 150.0)
        assertTrue("市区级 " + city + " 应比省际级 " + province + " 更细", city > province)

        val spanX = TileMath.worldX(121.4820, city) - TileMath.worldX(121.4730, city)
        val spanY = TileMath.worldY(31.2300, city) - TileMath.worldY(31.2390, city)
        assertTrue(spanX <= 300.0 + 1e-6)
        assertTrue(spanY <= 150.0 + 1e-6)

        if (city < TileMath.MAX_ZOOM) {
            val finerX = TileMath.worldX(121.4820, city + 1) - TileMath.worldX(121.4730, city + 1)
            val finerY = TileMath.worldY(31.2300, city + 1) - TileMath.worldY(31.2390, city + 1)
            assertTrue("再细一级就该装不下", finerX > 300.0 || finerY > 150.0)
        }
    }

    @Test
    fun `视图对空采样与非法画布返回 null`() {
        assertNull(TileMath.view(emptyList(), 300.0, 150.0, 12.0))
        assertNull(TileMath.view(shanghai, 0.0, 150.0, 12.0))
        assertNull(TileMath.view(shanghai, 300.0, -1.0, 12.0))
    }

    @Test
    fun `轨迹中心落在画布中心`() {
        val view = TileMath.view(shanghai, 300.0, 150.0, 12.0)
        assertTrue(view != null)
        val v = view!!
        assertEquals(150.0, TileMath.canvasX(121.4750, v), 1e-6)
        // 中心取的是「世界像素」的中点（Mercator 的 y 对纬度非线性，不是算术平均纬度）
        val midY = (TileMath.canvasY(31.2300, v) + TileMath.canvasY(31.2400, v)) / 2.0
        assertEquals(75.0, midY, 1e-6)
        // 瓦片级别比视图级别高一级，所以每张按 128dp 画
        assertEquals(128.0, v.tileSizeDp, 1e-6)
    }

    @Test
    fun `可见瓦片覆盖整个画布且列号在合法范围`() {
        val view = TileMath.view(shanghai, 300.0, 150.0, 12.0)!!
        val tiles = TileMath.visibleTiles(view, 300.0, 150.0)
        assertTrue(tiles.isNotEmpty())

        val side = tiles.first().sizeDp
        assertTrue("左上角要被盖住", tiles.any { it.leftDp <= 0.5f && it.topDp <= 0.5f })
        assertTrue("右侧要被盖住", tiles.any { it.leftDp + side >= 300f - 0.5f })
        assertTrue("底部要被盖住", tiles.any { it.topDp + side >= 150f - 0.5f })

        val last = (1 shl view.tileZoom) - 1
        assertTrue(tiles.all { it.z == view.tileZoom && it.x in 0..last && it.y in 0..last })
    }

    @Test
    fun `可见瓦片数量有上限保护`() {
        val view = TileMath.view(shanghai, 300.0, 150.0, 12.0)!!
        // 画布给得离谱大时应直接放弃（返回空）而不是算出上万个请求
        assertTrue(TileMath.visibleTiles(view, 200_000.0, 200_000.0).isEmpty())
    }

    @Test
    fun `缓存键带图源与级别`() {
        val ref = TileRef(z = 12, x = 1, y = 2, leftDp = 0f, topDp = 0f, sizeDp = 128f)
        assertEquals("osm/12/1/2", TileMath.tileKey("osm", ref))
    }

    @Test
    fun `瓦片模板展开全部入口且子域稳定`() {
        val urls = TileSources.OSM.urls(12, 3400, 1700)
        assertEquals("https://tile.openstreetmap.org/12/3400/1700.png", urls.first())
        assertEquals("三个入口都要展开", 3, urls.size)
        assertFalse(urls.any { it.contains("{") })
        // 同一块每次都落到同一台服务器，缓存才不会白白失效
        assertEquals(urls, TileSources.OSM.urls(12, 3400, 1700))
    }

    @Test
    fun `默认源的路径顺序是 z 斜杠 y 斜杠 x`() {
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/12/1700/3400",
            TileSources.DEFAULT.urls(12, 3400, 1700).first(),
        )
    }

    @Test
    fun `底图投影把轨迹点压进画布`() {
        val view = TileMath.view(shanghai, 300.0, 150.0, 12.0)!!
        val projection = TripTrace.projectOnMap(shanghai, emptyList(), view, 300f, 150f)
        assertTrue(projection.hasTrace)
        projection.points.forEach { point ->
            assertTrue("x=" + point.x, point.x in 0f..300f)
            assertTrue("y=" + point.y, point.y in 0f..150f)
        }
    }

    @Test
    fun `单点轨迹居中不缩放`() {
        val single = listOf(sample(31.2300, 121.4700))
        val view = TileMath.view(single, 300.0, 150.0, 12.0)!!
        assertEquals(150.0, TileMath.canvasX(121.4700, view), 1e-6)
        assertEquals(75.0, TileMath.canvasY(31.2300, view), 1e-6)
    }
}
