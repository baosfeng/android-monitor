package com.bsfeng.monitor.autobook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面变化检测测试（纯 JVM）。
 *
 * 核心回归：页面文本一变就处理，不再等固定时间窗；同一文本重复事件不重复解析。
 * 真机事故：支付页从过渡态到渲染完成约 940ms，旧 800ms 窗口把渲染完成的事件吞掉，浮窗慢约 1s。
 */
class AutoBookPageChangeDetectorTest {

    @Test
    fun `页面切换永远处理，即使文本与上次相同`() {
        val detector = AutoBookPageChangeDetector()
        assertTrue(detector.shouldProcess(isWindowStateChange = false, fingerprint = "A", nowMs = 1_000L))
        assertTrue(detector.shouldProcess(isWindowStateChange = true, fingerprint = "A", nowMs = 1_010L))
    }

    @Test
    fun `页面渲染完成文本变化立即处理，不等时间窗`() {
        val detector = AutoBookPageChangeDetector()
        assertTrue(detector.shouldProcess(false, "过渡态", 1_000L))
        // 真实场景约 940ms 后渲染完成；这里用 100ms 也应当处理（旧窗口会等到 1.15s）
        assertTrue(
            "文本变了就应该马上解析，不能等窗口到期",
            detector.shouldProcess(false, "完整账单页", 1_100L)
        )
    }

    @Test
    fun `同一文本重复事件不重复解析`() {
        val detector = AutoBookPageChangeDetector()
        assertTrue(detector.shouldProcess(false, "完整账单页", 1_000L))
        assertFalse(detector.shouldProcess(false, "完整账单页", 1_200L))
        assertFalse(detector.shouldProcess(false, "完整账单页", 3_000L))
    }

    @Test
    fun `同一帧内的连续变化只处理第一次`() {
        val detector = AutoBookPageChangeDetector()
        assertTrue(detector.shouldProcess(false, "第1行", 1_000L))
        // 50ms 内逐行渲染的变化先合并，避免事件风暴
        assertFalse(detector.shouldProcess(false, "第1行+第2行", 1_020L))
        // 超过最小间隔、内容仍在变化：处理最新文本
        assertTrue(detector.shouldProcess(false, "第1行+第2行+第3行", 1_100L))
    }

    @Test
    fun `超过最小间隔后同样的新内容仍会处理`() {
        val detector = AutoBookPageChangeDetector()
        assertTrue(detector.shouldProcess(false, "A", 1_000L))
        assertTrue(detector.shouldProcess(false, "B", 1_100L))
        assertTrue(detector.shouldProcess(false, "A", 1_200L))
    }
}
