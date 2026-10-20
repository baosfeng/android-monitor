package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮窗关闭路径的纯逻辑测试（2026-10-20 用户真机反馈后新增）。
 *
 * 契约：浮窗只能由「记一笔」或「忽略」关闭；点遮罩、切 App 都不关闭，只有服务销毁可被动卸载。
 */
class AutoBookDismissPolicyTest {

    @Test
    fun `点遮罩不关闭浮窗`() {
        // 真机事故：用户准备点「记一笔」时点到屏幕，浮窗立刻消失且同一笔 7 天不再弹
        val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.SCRIM_TAP)
        assertFalse("点遮罩绝不能关浮窗", effect.closeOverlay)
        assertFalse("点遮罩不是记账", effect.markConfirmed)
        assertFalse("点遮罩不是忽略（不能写 DISMISSED）", effect.markDismissed)
        assertFalse("点遮罩不能落盘 7 天忽略记忆", effect.persistIgnored)
        assertTrue("要给一次轻震动，让用户知道点到了", effect.hapticFeedback)
    }

    @Test
    fun `切到别的 App 不关闭浮窗`() {
        // 用户要求：切 App（包括切回本 App 记账页）浮窗都保持显示
        val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.APP_SWITCH)
        assertFalse(effect.closeOverlay)
        assertFalse(effect.persistIgnored)
        assertFalse(effect.markDismissed)
        assertFalse(effect.markConfirmed)
    }

    @Test
    fun `只有记一笔写已记账`() {
        val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.CONFIRM)
        assertTrue(effect.closeOverlay)
        assertTrue(effect.markConfirmed)
        assertFalse("记账不能写忽略记忆", effect.persistIgnored)
        assertFalse(effect.markDismissed)
    }

    @Test
    fun `只有忽略写忽略记忆`() {
        val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.IGNORE)
        assertTrue(effect.closeOverlay)
        assertTrue("忽略要写短期去重", effect.markDismissed)
        assertTrue("忽略要落盘 7 天", effect.persistIgnored)
        assertFalse(effect.markConfirmed)
    }

    @Test
    fun `服务销毁允许卸载浮窗但不写忽略`() {
        // 系统级生命周期：窗口留不住，但这不是用户的「忽略」
        val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.SERVICE_RELEASE)
        assertTrue(effect.closeOverlay)
        assertFalse(effect.persistIgnored)
        assertFalse(effect.markDismissed)
        assertFalse(effect.markConfirmed)
    }

    @Test
    fun `关闭浮窗的来源只有两个按钮加服务销毁`() {
        val closers = AutoBookCloseSource.entries
            .filter { AutoBookDismissPolicy.closesOverlay(it) }
        assertEquals(
            "只允许「记一笔」「忽略」与系统级销毁关浮窗",
            listOf(
                AutoBookCloseSource.CONFIRM,
                AutoBookCloseSource.IGNORE,
                AutoBookCloseSource.SERVICE_RELEASE
            ),
            closers
        )
    }

    @Test
    fun `写忽略记忆的来源只有忽略按钮`() {
        val writers = AutoBookCloseSource.entries
            .filter { AutoBookDismissPolicy.writesIgnore(it) }
        assertEquals(listOf(AutoBookCloseSource.IGNORE), writers)
    }
}
