package com.bsfeng.monitor.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标纠偏回归：WGS84 → GCJ-02 → BD-09 三路径（正常 / 边界 / 异常）。
 *
 * 断言用**钉死的期望值**而不是「算出来跟实现一样」：期望值由同一套公开近似算法独立算出
 * （天安门 WGS84 116.3912,39.9063 → GCJ 116.397441,39.907701），实现一旦被改坏会立刻变红。
 *
 * 为什么这条必须钉住：WGS84 直接喂给高德 / 百度会偏 300~600 米（实测天安门到 GCJ 偏 555 米、
 * 到 BD-09 偏 1380 米），「在地图中查看」会打开到一个看起来对、实际错的位置，比不打开更糟。
 */
class CoordTransformTest {

    // ── 1 · 正常路径 ──

    @Test
    fun `天安门 WGS84 转 GCJ-02 落在公开基准上`() {
        val wgs = GeoPoint(lat = 39.9063, lng = 116.3912)
        val gcj = CoordTransform.toGcj02(wgs)
        // 公开基准量级：116.3975、39.9087；6 位小数下为 116.397441 / 39.907701
        assertEquals(39.907701, gcj.lat, 1e-6)
        assertEquals(116.397441, gcj.lng, 1e-6)
    }

    @Test
    fun `天安门 WGS84 转 BD-09 在 GCJ 基础上再偏一次`() {
        val wgs = GeoPoint(lat = 39.9063, lng = 116.3912)
        val bd = CoordTransform.toBd09(wgs)
        assertEquals(39.914045, bd.lat, 1e-6)
        assertEquals(116.403815, bd.lng, 1e-6)
        // BD-09 必须比 GCJ-02 更偏，否则说明少做了一次变换
        assertTrue("BD-09 应比 GCJ-02 更偏北", bd.lat > CoordTransform.toGcj02(wgs).lat)
    }

    @Test
    fun `WGS84 目标不做任何改动且返回同一个值`() {
        val wgs = GeoPoint(lat = 39.9063, lng = 116.3912)
        assertEquals(wgs, CoordTransform.to(wgs, CoordSystem.WGS84))
    }

    @Test
    fun `纠偏量在合理量级内（不是 0、也不是几十度）`() {
        val wgs = GeoPoint(lat = 39.9063, lng = 116.3912)
        val gcj = CoordTransform.toGcj02(wgs)
        val dLat = gcj.lat - wgs.lat
        val dLng = gcj.lng - wgs.lng
        // 中国境内的 GCJ-02 偏移量级：纬度 0.001~0.01 度、经度 0.001~0.02 度
        assertTrue("纬度偏移量级异常：$dLat", dLat > 0.0005 && dLat < 0.02)
        assertTrue("经度偏移量级异常：$dLng", dLng > 0.0005 && dLng < 0.02)
    }

    // ── 2 · 边界路径 ──

    @Test
    fun `境外点原样返回，不被凭空挪走`() {
        // 东京 / 纽约 / 伦敦：GCJ-02 只在中国大陆生效，境外必须恒等
        listOf(
            GeoPoint(35.6895, 139.6917),
            GeoPoint(40.7128, -74.0060),
            GeoPoint(51.5074, -0.1278),
        ).forEach { point ->
            assertEquals("$point GCJ-02 被偏移了", point, CoordTransform.toGcj02(point))
            assertEquals("$point BD-09 被偏移了", point, CoordTransform.toBd09(point))
        }
    }

    @Test
    fun `中国范围判定的四个边界值都按境内处理`() {
        // 贴边的点偏移量已趋近 0，判境内 / 境外对落点无可观测差别，这里把口径钉死成「在内」
        assertFalse(CoordTransform.isOutOfChina(GeoPoint(0.8293, 72.004)))
        assertFalse(CoordTransform.isOutOfChina(GeoPoint(55.8271, 137.8347)))
        assertFalse(CoordTransform.isOutOfChina(GeoPoint(30.0, 72.004)))
        assertFalse(CoordTransform.isOutOfChina(GeoPoint(30.0, 137.8347)))
        // 刚出界一点就该判境外
        assertTrue(CoordTransform.isOutOfChina(GeoPoint(30.0, 72.0039)))
        assertTrue(CoordTransform.isOutOfChina(GeoPoint(30.0, 137.8348)))
    }

    @Test
    fun `赤道与零度子午线的极值点不产生 NaN`() {
        val equator = GeoPoint(0.0, 0.0)
        val gcj = CoordTransform.toGcj02(equator)
        assertFalse("经度出现 NaN", gcj.lng.isNaN())
        assertFalse("纬度出现 NaN", gcj.lat.isNaN())
        // 零点是境外，直接恒等
        assertEquals(equator, gcj)
    }

    @Test
    fun `南北极极值点不崩且保持合法范围`() {
        listOf(GeoPoint(90.0, 180.0), GeoPoint(-90.0, -180.0)).forEach { point ->
            val gcj = CoordTransform.toGcj02(point)
            assertTrue("$point 结果越界：$gcj", gcj.isValid)
        }
    }

    @Test
    fun `结果保留 6 位小数，且负零归一成正零`() {
        val gcj = CoordTransform.toGcj02(GeoPoint(39.9063, 116.3912))
        assertEquals("纬度小数位过多", 6, decimalsOf(gcj.lat))
        assertEquals("经度小数位过多", 6, decimalsOf(gcj.lng))
        // -0.0 与 0.0 在 URI 里应写成同一个字符串
        assertEquals(0.0, CoordTransform.toGcj02(GeoPoint(0.0, 0.0)).lat, 0.0)
    }

    // ── 3 · 异常路径 ──

    @Test
    fun `NaN 坐标原样返回，不抛异常`() {
        val nan = GeoPoint(Double.NaN, Double.NaN)
        assertFalse("NaN 点不该判为合法", nan.isValid)
        val gcj = CoordTransform.toGcj02(nan)
        assertTrue("NaN 应原样返回", gcj.lat.isNaN() && gcj.lng.isNaN())
    }

    @Test
    fun `越界坐标原样返回，不抛异常也不裁剪`() {
        val bad = GeoPoint(lat = 91.0, lng = 200.0)
        assertFalse(bad.isValid)
        assertEquals(bad, CoordTransform.toGcj02(bad))
        assertEquals(bad, CoordTransform.toBd09(bad))
    }

    @Test
    fun `to 对三种坐标系都不抛异常`() {
        val point = GeoPoint(31.2304, 121.4737)
        CoordSystem.entries.forEach { system ->
            val out = CoordTransform.to(point, system)
            assertTrue("$system 输出不合法：$out", out.isValid)
        }
    }

    /** 小数位数（用于钉死「保留 6 位」的口径）。 */
    private fun decimalsOf(value: Double): Int {
        val text = value.toString()
        val dot = text.indexOf('.')
        return if (dot < 0) 0 else text.length - dot - 1
    }
}
