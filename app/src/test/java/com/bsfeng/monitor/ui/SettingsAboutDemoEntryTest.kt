package com.bsfeng.monitor.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s09-goto-demo 入口可见性。
 *
 * 契约 s09:43 · 边界：调试模式关闭时本行仍可进入 —— 因此判据只有 debug 构建 + 长按解锁。
 */
class SettingsAboutDemoEntryTest {

    @Test
    fun `调试模式关闭时演示入口仍可见`() {
        assertTrue(demoEntryVisible(debugUi = true, debugOn = false))
    }

    @Test
    fun `调试模式开启时演示入口可见`() {
        assertTrue(demoEntryVisible(debugUi = true, debugOn = true))
    }

    @Test
    fun `release 构建或未解锁时不渲染演示入口`() {
        assertFalse(demoEntryVisible(debugUi = false, debugOn = true))
        assertFalse(demoEntryVisible(debugUi = false, debugOn = false))
    }
}
