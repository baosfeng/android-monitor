package com.bsfeng.monitor.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URI 拼装回归：每家地图吃的坐标系不同，拼错了就是 300~600 米的落点偏差。
 *
 * 三个必须钉死的点：
 * 1. 高德 / 腾讯的 URI 里必须是 **GCJ-02** 数值（不是采集到的 WGS84）；
 * 2. 百度的 URI 里必须是 **BD-09**，且带 `coord_type=bd09ll`（不带会被按 gcj02 解释）；
 * 3. 浏览器兜底里必须是 **WGS84**（OSM 不认 GCJ-02）。
 */
class MapUriTest {

    private val tiananmen = GeoPoint(lat = 39.9063, lng = 116.3912)

    // ── 1 · 正常路径：坐标系统一 ──

    @Test
    fun `高德 URI 用 GCJ-02 且带来源标识`() {
        val uri = MapUri.uriFor(MapTarget.AMAP, tiananmen, "天安门", "com.bsfeng.monitor")!!
        assertTrue(uri.startsWith("androidamap://viewMap?"))
        assertTrue("缺 sourceApplication：$uri", uri.contains("sourceApplication=com.bsfeng.monitor"))
        assertTrue("纬度不是 GCJ-02：$uri", uri.contains("lat=39.907701"))
        assertTrue("经度不是 GCJ-02：$uri", uri.contains("lon=116.397441"))
        assertTrue("缺 dev 参数：$uri", uri.contains("dev=0"))
    }

    @Test
    fun `腾讯 URI 用 GCJ-02 且把标题拼进 marker`() {
        val uri = MapUri.uriFor(MapTarget.TENCENT, tiananmen, "天安门")!!
        assertTrue(uri.startsWith("qqmap://map/marker?marker="))
        assertTrue("纬度不是 GCJ-02：$uri", uri.contains("39.907701"))
        assertTrue("经度不是 GCJ-02：$uri", uri.contains("116.397441"))
        assertTrue("标题被 URL 编码后应出现：$uri", uri.contains("%E5%A4%A9%E5%AE%89%E9%97%A8"))
    }

    @Test
    fun `百度 URI 用 BD-09 且显式声明 coord_type`() {
        val uri = MapUri.uriFor(MapTarget.BAIDU, tiananmen, "天安门")!!
        assertTrue(uri.startsWith("baidumap://map/marker?location="))
        assertTrue("纬度不是 BD-09：$uri", uri.contains("39.914045"))
        assertTrue("经度不是 BD-09：$uri", uri.contains("116.403815"))
        assertTrue("缺 coord_type，百度会按 gcj02 解释：$uri", uri.contains("coord_type=bd09ll"))
        assertTrue("缺 src：$uri", uri.contains("src="))
    }

    @Test
    fun `Google URI 用 WGS84 原样坐标`() {
        val uri = MapUri.uriFor(MapTarget.GOOGLE, tiananmen, "天安门")!!
        assertTrue(uri.startsWith("geo:39.906300,116.391200"))
        assertTrue("q 参数缺坐标：$uri", uri.contains("q=39.906300,116.391200"))
    }

    @Test
    fun `浏览器兜底用 OSM 且是 WGS84`() {
        val uri = MapUri.uriFor(MapTarget.BROWSER, tiananmen, "天安门")!!
        assertTrue(uri.startsWith("https://www.openstreetmap.org/"))
        assertTrue("缺 mlat：$uri", uri.contains("mlat=39.906300"))
        assertTrue("缺 mlon：$uri", uri.contains("mlon=116.391200"))
        // 浏览器不能吃 GCJ-02 数值，否则 OSM 上会偏
        assertFalse("浏览器 URI 混入了 GCJ-02 坐标：$uri", uri.contains("116.397441"))
        assertTrue("缺缩放级别：$uri", uri.contains("#map=16/39.906300/116.391200"))
    }

    // ── 2 · 边界路径 ──

    @Test
    fun `地名空缺时不拼空标题参数`() {
        val amap = MapUri.uriFor(MapTarget.AMAP, tiananmen, null)!!
        assertTrue("空地名仍应保留 poiname= 键：$amap", amap.contains("poiname="))
        assertFalse("poiname 不该有实际内容：$amap", amap.contains("poiname=%"))

        val baidu = MapUri.uriFor(MapTarget.BAIDU, tiananmen, "   ")!!
        assertFalse("空白地名应归一成 null：$baidu", baidu.contains("&title="))

        val google = MapUri.uriFor(MapTarget.GOOGLE, tiananmen, "")!!
        assertFalse("空地名不该产生括号：$google", google.contains("("))
    }

    @Test
    fun `负零归一成 0 且负坐标带负号`() {
        // 纽约（境外，不纠偏）
        val ny = GeoPoint(lat = 40.7128, lng = -74.006)
        val google = MapUri.uriFor(MapTarget.GOOGLE, ny, null)!!
        assertTrue("负经度丢了负号：$google", google.contains("40.712800,-74.006000"))

        // 零度点：不能出现 -0.000000
        val zero = GeoPoint(0.0, 0.0)
        val zeroUri = MapUri.uriFor(MapTarget.GOOGLE, zero, null)!!
        assertTrue(zeroUri.contains("geo:0.000000,0.000000"))
        assertFalse("出现负零：$zeroUri", zeroUri.contains("-0.000000"))
    }

    @Test
    fun `小数位固定 6 位，与 Locale 无关`() {
        assertEquals("39.906300", MapUri.formatLat(39.9063))
        assertEquals("-74.006000", MapUri.formatLng(-74.006))
        assertEquals("0.000000", MapUri.formatLat(0.0))
        assertEquals("180.000000", MapUri.formatLng(180.0))
        assertEquals("-180.000000", MapUri.formatLng(-180.0))
    }

    @Test
    fun `极值坐标被夹到合法范围`() {
        // 超出 ±90 / ±180 的值夹住而不是拼成非法 URI（构造本身不抛）
        assertEquals("90.000000", MapUri.formatLat(120.0))
        assertEquals("-90.000000", MapUri.formatLat(-120.0))
        assertEquals("180.000000", MapUri.formatLng(200.0))
    }

    @Test
    fun `地名里的空格与特殊字符被转义`() {
        val uri = MapUri.uriFor(MapTarget.AMAP, tiananmen, "A&B C")!!
        assertTrue("& 未转义会截断参数：$uri", uri.contains("poiname=A%26B+C"))
    }

    // ── 3 · 异常路径 ──

    @Test
    fun `非法坐标返回 null 而不是拼一条坏 URI`() {
        assertNull(MapUri.uriFor(MapTarget.AMAP, GeoPoint(Double.NaN, 0.0), null))
        assertNull(MapUri.uriFor(MapTarget.BAIDU, GeoPoint(0.0, 181.0), null))
        MapTarget.entries.forEach { target ->
            assertNull("$target 对越界坐标应返回 null", MapUri.uriFor(target, GeoPoint(91.0, 0.0), null))
        }
    }

    @Test
    fun `每个目标对合法坐标都能拼出非空 URI`() {
        MapTarget.entries.forEach { target ->
            val uri = MapUri.uriFor(target, tiananmen, "测试点")
            assertTrue("$target 没拼出 URI", uri != null && uri.isNotBlank())
        }
    }
}
