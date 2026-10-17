package com.bsfeng.monitor.autobook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件节流测试（纯 JVM）。
 *
 * 复现真机事故：支付成功页的 WINDOW_STATE_CHANGED 落在指纹页之后 402ms，
 * 被 CONTENT_CHANGED 的 800ms 窗口吃掉 → 静态结果页不再有事件 → 浮窗永远不弹。
 */
class AutoBookEventThrottleTest {

    @Test
    fun `页面切换永远放行，即使落在内容节流窗口内`() {
        val throttle = AutoBookEventThrottle()
        assertTrue(throttle.shouldProcess(isWindowStateChange = false, nowMs = 0L))
        assertTrue(
            "402ms 后的支付成功页必须放行 —— 旧实现就是在这里丢掉事件的",
            throttle.shouldProcess(isWindowStateChange = true, nowMs = 402L)
        )
    }

    @Test
    fun `连续内容变化在 800ms 窗口内被挡住`() {
        val throttle = AutoBookEventThrottle()
        assertTrue(throttle.shouldProcess(false, 1_000L))
        assertFalse(throttle.shouldProcess(false, 1_500L))
    }

    @Test
    fun `内容变化超过窗口后放行`() {
        val throttle = AutoBookEventThrottle()
        assertTrue(throttle.shouldProcess(false, 1_000L))
        assertTrue(throttle.shouldProcess(false, 1_800L))
    }

    @Test
    fun `页面切换后紧跟的内容变化仍按窗口节流`() {
        val throttle = AutoBookEventThrottle()
        assertTrue(throttle.shouldProcess(true, 2_000L))
        assertFalse(throttle.shouldProcess(false, 2_300L))
        assertTrue(throttle.shouldProcess(false, 2_800L))
    }
}
