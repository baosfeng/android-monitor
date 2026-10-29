package com.bsfeng.monitor.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s09-skip 关闭态两行入口的副标题。
 *
 * 契约 s09:37：组内两行常驻；跳过引擎关闭时两行仍可进入，只改副标题文案。
 */
class SettingsSkipFeatureRowsTest {

    @Test
    fun `跳过引擎关闭时规则行仍标明已关闭且保留入口`() {
        val subtitle = skipRulesRowSubtitle(adSkipOn = false, subscriptionCount = 2)
        assertTrue(subtitle, subtitle.startsWith("已关闭"))
        assertTrue(subtitle, subtitle.contains("订阅源 2 个"))
    }

    @Test
    fun `跳过引擎关闭时白名单行仍可维护`() {
        val subtitle = whitelistRowSubtitle(adSkipOn = false)
        assertTrue(subtitle, subtitle.startsWith("已关闭"))
        assertTrue(subtitle, subtitle.contains("白名单"))
    }

    @Test
    fun `开启态不出现已关闭字样`() {
        assertFalse(skipRulesRowSubtitle(adSkipOn = true, subscriptionCount = 2).contains("已关闭"))
        assertFalse(whitelistRowSubtitle(adSkipOn = true).contains("已关闭"))
    }

    @Test
    fun `两行关闭态文案必须不同`() {
        // 相同文案等于把两行抹成一行，用户分不清点进去是规则还是白名单
        assertNotEquals(
            skipRulesRowSubtitle(adSkipOn = false, subscriptionCount = 0),
            whitelistRowSubtitle(adSkipOn = false),
        )
    }
}
