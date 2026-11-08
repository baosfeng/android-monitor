package com.bsfeng.monitor.service

import android.content.Context
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 前台轮询的熄屏门禁（R11）。
 *
 * 回归点：旧实现每个 tick 无条件 rootInActiveWindow（跨进程 Binder），息屏后必然拿到
 * null，8 小时约 2.9 万次空取。这里钉住「熄屏不取窗」与「节拍拉长到 30 秒」两条判据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundPollPolicyTest {

    @Test
    fun `熄屏不取根窗口 亮屏才取`() {
        assertFalse(ForegroundPollPolicy.shouldQueryWindow(interactive = false))
        assertTrue(ForegroundPollPolicy.shouldQueryWindow(interactive = true))
    }

    @Test
    fun `熄屏节拍拉长到 30 秒 亮屏恢复逐秒`() {
        assertEquals(ForegroundPollPolicy.SCREEN_OFF_INTERVAL_MS, ForegroundPollPolicy.nextDelayMs(false))
        assertEquals(ForegroundPollPolicy.INTERACTIVE_INTERVAL_MS, ForegroundPollPolicy.nextDelayMs(true))
        assertEquals("亮屏保持 1 秒", 1_000L, ForegroundPollPolicy.nextDelayMs(true))
        assertEquals("熄屏 30 秒", 30_000L, ForegroundPollPolicy.nextDelayMs(false))
    }

    @Test
    fun `PowerManager 读数映射到门禁判定`() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        shadowOf(pm).setIsInteractive(false)
        assertFalse(context.isScreenInteractive())
        assertFalse("熄屏时这一拍不应取窗", ForegroundPollPolicy.shouldQueryWindow(context.isScreenInteractive()))

        shadowOf(pm).setIsInteractive(true)
        assertTrue(context.isScreenInteractive())
        assertTrue(ForegroundPollPolicy.shouldQueryWindow(context.isScreenInteractive()))
    }
}
