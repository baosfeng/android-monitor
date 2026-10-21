package com.bsfeng.monitor.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能开关的纯 JVM 测试（无 Robolectric）：默认值 / 读写落盘 / 关闭后 Tab 列表 / 深链回退。
 *
 * 默认值由产品拍板：**传送默认关闭，其余功能默认开启**，所以「传送关」是契约而不是实现细节。
 */
class FeatureFlagsTest {

    /** 内存后端：模拟 SharedPreferences，可断言「确实落盘了」。 */
    private class FakeStore(initial: Map<Feature, Boolean> = emptyMap()) : FeatureStore {
        val saved = initial.toMutableMap()
        var writes = 0

        override fun read(feature: Feature): Boolean? = saved[feature]

        override fun write(feature: Feature, enabled: Boolean) {
            writes++
            saved[feature] = enabled
        }
    }

    @Test
    fun `默认值 传送关闭 其余开启`() {
        val flags = FeatureFlags(FakeStore())

        assertFalse("传送默认关闭", flags.isEnabled(Feature.TRANSPORT))
        assertTrue("实时默认开启", flags.isEnabled(Feature.LIVE))
        assertTrue("广告跳过默认开启", flags.isEnabled(Feature.AD_SKIP))
        assertTrue("记账默认开启", flags.isEnabled(Feature.FINANCE))
        // 默认值集中一处：Catalog 与 FeatureFlags 必须一致
        assertEquals(FeatureCatalog.defaultOf(Feature.TRANSPORT), flags.isEnabled(Feature.TRANSPORT))
    }

    @Test
    fun `写入后即时生效 且下次启动从存储读回`() {
        val store = FakeStore()
        val flags = FeatureFlags(store)

        assertTrue("值变化应返回 true", flags.setEnabled(Feature.TRANSPORT, true))
        assertTrue(flags.isEnabled(Feature.TRANSPORT))
        assertEquals(true, flags.snapshot()[Feature.TRANSPORT])
        assertEquals("写入必须落盘", true, store.saved[Feature.TRANSPORT])

        // 重建实例 = 重启进程：仍读到 true，而不是回落到默认值 false
        assertTrue(FeatureFlags(store).isEnabled(Feature.TRANSPORT))

        // 关闭同理，并且要落盘 false（不能只是删键）
        assertTrue(flags.setEnabled(Feature.LIVE, false))
        assertFalse(flags.isEnabled(Feature.LIVE))
        assertEquals(false, store.saved[Feature.LIVE])
        assertFalse(FeatureFlags(store).isEnabled(Feature.LIVE))
    }

    @Test
    fun `重复设置同一值不写盘`() {
        val store = FakeStore()
        val flags = FeatureFlags(store)

        // 传送默认关：再关一次应短路
        assertFalse(flags.setEnabled(Feature.TRANSPORT, false))
        // 实时默认开：再开一次应短路
        assertFalse(flags.setEnabled(Feature.LIVE, true))
        assertEquals("重复设置不应产生写盘", 0, store.writes)
    }

    @Test
    fun `关闭传送 不生成传送 Tab`() {
        val flags = FeatureFlags(FakeStore())

        val tabs = flags.visibleTabs()
        assertFalse("传送关闭后不应有传送 Tab", AppTab.TRANSFER in tabs)
        assertEquals(
            listOf(AppTab.OVERVIEW, AppTab.LIVE, AppTab.AD, AppTab.FINANCE, AppTab.SETTINGS),
            tabs,
        )

        // 重新开启：传送 Tab 回到原位置（概览 / 实时 / 广告 / 记账 / 传送 / 设置）
        flags.setEnabled(Feature.TRANSPORT, true)
        assertEquals(AppTab.entries, flags.visibleTabs())
    }

    @Test
    fun `关闭广告跳过 不影响广告 Tab`() {
        val flags = FeatureFlags(FakeStore())

        flags.setEnabled(Feature.AD_SKIP, false)

        // 广告 Tab 是「广告事件记录页」，不是跳过引擎本身：引擎关了记录照看
        assertTrue(AppTab.AD in flags.visibleTabs())
        assertFalse("规则引擎侧读到的是关闭", flags.isEnabled(Feature.AD_SKIP))
        assertEquals(
            "常驻 Tab（概览 / 广告 / 设置）不因任何开关消失",
            listOf(AppTab.OVERVIEW, AppTab.LIVE, AppTab.AD, AppTab.FINANCE, AppTab.SETTINGS),
            flags.visibleTabs(),
        )
    }

    @Test
    fun `关闭实时与记账 对应 Tab 一并消失 且概览与设置始终在`() {
        val flags = FeatureFlags(FakeStore())

        flags.setEnabled(Feature.LIVE, false)
        flags.setEnabled(Feature.FINANCE, false)

        // 实时 / 记账被关，传送本来默认关 → 只剩常驻的概览 / 广告 / 设置
        assertEquals(
            listOf(AppTab.OVERVIEW, AppTab.AD, AppTab.SETTINGS),
            flags.visibleTabs(),
        )
    }

    @Test
    fun `深链落在已关闭功能上时回退概览`() {
        val flags = FeatureFlags(FakeStore())

        // 传送默认关：通知点进来必须落概览，而不是空白 Tab
        assertEquals(AppTab.OVERVIEW, flags.resolveTab(AppTab.TRANSFER))
        // 未关闭的目标照常命中
        assertEquals(AppTab.LIVE, flags.resolveTab(AppTab.LIVE))
        // 常驻 Tab 不受影响
        assertEquals(AppTab.SETTINGS, flags.resolveTab(AppTab.SETTINGS))

        flags.setEnabled(Feature.TRANSPORT, true)
        assertEquals(AppTab.TRANSFER, flags.resolveTab(AppTab.TRANSFER))
        // 越界 / 非法下标回退
        assertEquals(AppTab.OVERVIEW, FeatureCatalog.tabOfIndex(99))
        assertEquals(AppTab.OVERVIEW, FeatureCatalog.tabOfIndex(-1))
        assertEquals(AppTab.OVERVIEW, flags.resolveTab(FeatureCatalog.tabOfIndex(99)))
    }
}
