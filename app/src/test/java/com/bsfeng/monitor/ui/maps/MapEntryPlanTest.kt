package com.bsfeng.monitor.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选计划回归：决定「点入口后到底弹不弹选择、会不会落到浏览器」。
 *
 * 纯 JVM 用例：`build` 只吃「已装包集合」，因此可以把「只装高德」「全装」「一个没装」等组合
 * 一次跑完，不需要设备，也不需要 Robolectric。
 */
class MapEntryPlanTest {

    private val point = GeoPoint(lat = 39.9063, lng = 116.3912)

    private fun plan(installed: Set<String>) =
        MapEntryPlan.build(installed, point, "天安门", "com.bsfeng.monitor")

    // ── 1 · 正常路径 ──

    @Test
    fun `只装了高德时恰好一个候选，直接打开不弹选择`() {
        val plan = plan(setOf("com.autonavi.minimap"))
        assertFalse("一个候选不该弹选择器", plan.needsPicker)
        assertEquals(MapTarget.AMAP, plan.single?.target)
    }

    @Test
    fun `装了多家时按偏好顺序排全部地图，浏览器不混进选择器`() {
        val plan = plan(MapTarget.QUERY_PACKAGES.toSet())
        assertTrue("多个候选必须弹选择器", plan.needsPicker)
        assertEquals(
            listOf(MapTarget.AMAP, MapTarget.TENCENT, MapTarget.BAIDU, MapTarget.GOOGLE),
            plan.candidates.map { it.target },
        )
        assertFalse("有地图时浏览器不得出现在候选里", plan.candidates.any { it.target == MapTarget.BROWSER })
        assertFalse(plan.usingBrowserFallback)
    }

    @Test
    fun `浏览器兜底只在没有任何地图时出现，且用 WGS84 坐标`() {
        val web = plan(emptySet())
        assertEquals(MapTarget.BROWSER, web.single?.target)
        assertTrue("兜底 URI 应走 https：" + web.single!!.uri, web.single!!.uri.startsWith("https://"))
        assertTrue("兜底不该用 GCJ-02：" + web.single!!.uri, web.single!!.uri.contains("mlat=39.906300"))
        assertTrue("落到浏览器时要给用户说明", web.usingBrowserFallback)
    }

    @Test
    fun `候选 URI 与目标坐标系一致`() {
        val plan = plan(MapTarget.QUERY_PACKAGES.toSet())
        plan.candidates.forEach { candidate ->
            val expected = CoordTransform.to(point, candidate.target.coordSystem)
            assertTrue(
                "${candidate.target} 的 URI 没带上 ${candidate.target.coordSystem} 坐标：${candidate.uri}",
                candidate.uri.contains(MapUri.formatLat(expected.lat)),
            )
        }
    }

    // ── 2 · 边界路径 ──

    @Test
    fun `一个地图都没装时只剩浏览器一个候选`() {
        val plan = plan(emptySet())
        assertFalse("只剩兜底时不弹选择器", plan.needsPicker)
        assertEquals(MapTarget.BROWSER, plan.single?.target)
    }

    @Test
    fun `装了地图包但没装浏览器时，候选仍只有已装的地图`() {
        // build 不判浏览器是否真能解析；这一步由 MapLauncher.planFor 在 Android 侧复核
        val plan = plan(setOf("com.baidu.BaiduMap"))
        assertEquals(MapTarget.BAIDU, plan.single?.target)
    }

    @Test
    fun `未登记在 MAP_APPS 里的包名被忽略`() {
        val plan = plan(setOf("com.example.notamap"))
        // 未知包不影响兜底，但绝不能被当成能用 —— 候选里只有浏览器
        assertEquals(1, plan.candidates.size)
        assertEquals(MapTarget.BROWSER, plan.candidates.single().target)
    }

    // ── 3 · 异常路径 ──

    @Test
    fun `非法坐标返回空计划，不产生任何候选`() {
        for (bad in listOf(GeoPoint(Double.NaN, 116.0), GeoPoint(91.0, 0.0), GeoPoint(0.0, 181.0))) {
            val plan = MapEntryPlan.build(MapTarget.QUERY_PACKAGES.toSet(), bad, null, "com.bsfeng.monitor")
            assertTrue("$bad 不该产生候选", plan.candidates.isEmpty())
            assertNull(plan.single)
            assertFalse(plan.needsPicker)
        }
    }

    @Test
    fun `空计划给出无浏览器文案而不是静默无反应`() {
        assertTrue(MapEntryPlan.NO_BROWSER_TEXT.isNotBlank())
        assertTrue(MapEntryPlan.NO_MAP_TEXT.isNotBlank())
    }

    @Test
    fun `地图包名清单与枚举一致，manifest 的 queries 不会漏包`() {
        assertEquals(
            listOf("com.autonavi.minimap", "com.tencent.map", "com.baidu.BaiduMap", "com.google.android.apps.maps"),
            MapTarget.QUERY_PACKAGES,
        )
        // 浏览器不绑定包名，不能出现在 queries 清单里（否则白要一次包可见性）
        assertFalse(MapTarget.QUERY_PACKAGES.contains(MapTarget.BROWSER.packageName))
        assertTrue(MapTarget.MAP_APPS.all { it.isMapApp })
        assertFalse(MapTarget.BROWSER.isMapApp)
    }
}
