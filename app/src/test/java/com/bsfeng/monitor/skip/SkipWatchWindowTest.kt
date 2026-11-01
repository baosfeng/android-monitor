package com.bsfeng.monitor.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配窗口状态（纯 JVM）：起止、扫描计数、内容变化节流、弱网顺延的四类拒绝分支。
 *
 * 顺延判定同时看三件事：策略启用、顺延次数、页面是否仍在渲染、引擎是否一直在连续扫描。
 */
class SkipWatchWindowTest {

    private val window = SkipWatchWindow()
    private val policy = WindowExtendPolicy(stepMs = 4_000L, maxExtensions = 2, stillRenderingMs = 2_000L, maxScanGapMs = 1_500L)

    @Test
    fun `初始状态为空窗口`() {
        assertFalse(window.watching)
        assertEquals(0, window.scanCount)
        assertEquals(0L, window.deadlineMs)
        assertEquals(0L, window.extensionGrantedMs)
    }

    @Test
    fun `进入与开窗重置窗口状态`() {
        window.markEnter(now = 500L)
        assertEquals(500L, window.enterTimeMs)
        window.start(now = 1_000L, windowMs = 10_000L)
        assertTrue(window.watching)
        assertEquals(11_000L, window.deadlineMs)
        window.countScan()
        window.countScan()
        assertEquals(2, window.scanCount)
        // 重新开窗清零计数与顺延额度
        window.start(now = 2_000L, windowMs = 10_000L)
        assertEquals(0, window.scanCount)
        assertEquals(12_000L, window.deadlineMs)
        window.stop()
        assertFalse(window.watching)
        assertEquals(0, window.scanCount)
    }

    @Test
    fun `内容变化节流`() {
        assertTrue(window.noteContentChanged(now = 1_000L, throttleMs = 500L))
        assertFalse(window.noteContentChanged(now = 1_200L, throttleMs = 500L))
        assertTrue(window.noteContentChanged(now = 1_500L, throttleMs = 500L))
        // 节流间隔为 0 时每次都算变化
        assertTrue(window.noteContentChanged(now = 1_500L, throttleMs = 0L))
    }

    @Test
    fun `扫描间隔首次为空之后是差值`() {
        assertNull(window.beginScan(now = 1_000L))
        assertEquals(250L, window.beginScan(now = 1_250L))
        assertEquals(0L, window.beginScan(now = 1_250L))
    }

    @Test
    fun `顺延在策略关闭时拒绝`() {
        window.start(now = 0L, windowMs = 10_000L)
        val off = WindowExtendPolicy(stepMs = 0L, maxExtensions = 0)
        assertFalse(window.tryExtend("app", now = 10_000L, sinceLastScanMs = 100L, policy = off))
        assertEquals(10_000L, window.deadlineMs)
    }

    @Test
    fun `顺延次数用尽后拒绝`() {
        window.start(now = 0L, windowMs = 10_000L)
        window.noteContentChanged(now = 1_000L, throttleMs = 0L)
        assertTrue(window.tryExtend("app", now = 1_000L, sinceLastScanMs = 100L, policy = policy))
        assertEquals(14_000L, window.deadlineMs)
        window.noteContentChanged(now = 5_000L, throttleMs = 0L)
        assertTrue(window.tryExtend("app", now = 5_000L, sinceLastScanMs = 100L, policy = policy))
        assertEquals(18_000L, window.deadlineMs)
        assertEquals(8_000L, window.extensionGrantedMs)
        assertEquals(2, window.extensionCount)
        window.noteContentChanged(now = 9_000L, throttleMs = 0L)
        assertFalse(window.tryExtend("app", now = 9_000L, sinceLastScanMs = 100L, policy = policy))
    }

    @Test
    fun `扫描长时间中断视为窗口过期不顺延`() {
        window.start(now = 0L, windowMs = 10_000L)
        window.noteContentChanged(now = 9_999L, throttleMs = 0L)
        assertFalse(window.tryExtend("app", now = 10_000L, sinceLastScanMs = 10_000L, policy = policy))
        assertEquals(10_000L, window.deadlineMs)
    }

    @Test
    fun `页面静止不顺延`() {
        window.start(now = 0L, windowMs = 10_000L)
        window.noteContentChanged(now = 1_000L, throttleMs = 0L)
        assertFalse(window.tryExtend("app", now = 10_000L, sinceLastScanMs = 100L, policy = policy))
        // 窗口内从未有内容变化：同样视为静止
        window.start(now = 0L, windowMs = 10_000L)
        assertFalse(window.tryExtend("app", now = 10_000L, sinceLastScanMs = null, policy = policy))
    }

    @Test
    fun `顺延把截止时间按原截止点后推`() {
        window.start(now = 0L, windowMs = 10_000L)
        // 到点检测晚了 2s，但页面仍有内容变化（距最近一次变化 < stillRenderingMs）
        window.noteContentChanged(now = 11_500L, throttleMs = 0L)
        // 截止仍按原截止时间 + 步长，而不是 now + 步长
        assertTrue(window.tryExtend("app", now = 12_000L, sinceLastScanMs = 100L, policy = policy))
        assertEquals(14_000L, window.deadlineMs)
        assertTrue(window.extensionGrantedMs == 4_000L)
    }
}
