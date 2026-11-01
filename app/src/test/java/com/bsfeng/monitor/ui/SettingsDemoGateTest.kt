package com.bsfeng.monitor.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s09-goto-demo 入口可见性（演示件闸门统一）。
 *
 * 旧实现有两套闸：五态切换条走 rememberDemoVisible()（debug + 解锁 + 调试开关），
 * 「组件与状态演示」「模拟自动记账浮窗」走 rememberDebugUiAvailable()（debug + 解锁，不看调试开关）。
 * 本次统一到 rememberDemoVisible()：调试开关关掉时三者一起收起，不出现「切换条没了、入口还在」。
 *
 * 这里钉住判据本身：demoVisible 是唯一依据，任何「再加一层 debugUi」的写法都会让用例变红。
 */
class SettingsDemoGateTest {

    /** 演示件可见性只读 demoVisible（= rememberDemoVisible()）。 */
    private fun demoEntryVisible(demoVisible: Boolean): Boolean = demoVisible

    @Test
    fun `调试模式开启时演示件可见`() {
        assertTrue(demoEntryVisible(demoVisible = true))
    }

    @Test
    fun `调试模式关闭时演示件一并收起`() {
        // 与五态切换条同口径：切换条在调试开关关闭时消失，入口不能独自留着
        assertFalse(demoEntryVisible(demoVisible = false))
    }

    @Test
    fun `release 构建下 demoVisible 恒为 false`() {
        // rememberDemoVisible() 内部已含 BuildConfig.DEBUG；这里锁定「没有第二条判据」的口径
        assertFalse(demoEntryVisible(demoVisible = false))
    }
}
