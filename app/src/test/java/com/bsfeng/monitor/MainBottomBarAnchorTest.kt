package com.bsfeng.monitor

import com.bsfeng.monitor.feature.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部 Tab 栏的契约锚点映射（s21 契约 §tabbar / tab-* 段）。
 *
 * 只锁两件事：契约写明的 6 个 Tab 各有锚点且互不相同；传送 Tab 契约未定义、必须返回 null
 * 而不是自造锚点——否则机器对账会出现「代码有、契约无」的自增项。
 */
class MainBottomBarAnchorTest {

    private val definedTabs = listOf(
        AppTab.OVERVIEW to "tab-overview",
        AppTab.LIVE to "tab-live",
        AppTab.AD to "tab-ad",
        AppTab.FINANCE to "tab-finance",
        AppTab.SETTINGS to "tab-settings",
        AppTab.QUOTA to "tab-quota",
    )

    @Test
    fun `契约定义的六个 Tab 锚点逐字一致`() {
        for ((tab, expected) in definedTabs) {
            assertEquals("${tab.name} 的锚点必须与契约逐字相同", expected, tab.contractAnchor())
        }
    }

    @Test
    fun `传送 Tab 契约未定义锚点_返回 null 不自增`() {
        assertNull("s21 契约没有 tab-transfer，不能自造", AppTab.TRANSFER.contractAnchor())
    }

    @Test
    fun `已定义锚点互不重复`() {
        val anchors = definedTabs.map { it.second }
        assertEquals("锚点不能重复", anchors.size, anchors.toSet().size)
    }

    @Test
    fun `每个已定义锚点都以 tab 开头`() {
        for ((tab, anchor) in definedTabs) {
            assertTrue("${tab.name} 的锚点 $anchor 应以 tab- 开头", anchor.startsWith("tab-"))
        }
    }
}
