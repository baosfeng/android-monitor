package com.bsfeng.monitor.transfer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动关闭接收」的纯逻辑测试（纯 JVM，不依赖 Android 框架）。
 *
 * 钉住四条契约：
 * 1. 服务启动成功后起算，到点即该停（到点停止）；
 * 2. 手动关闭服务取消计时，重新开启重新计时（新到期时刻严格晚于旧的）；
 * 3. 进程重启后不恢复计时（状态只在内存，`deadlineMs() == 0`）；
 * 4. 剩余时间文案边界：60 秒 / 0 秒 / 4 分钟。
 */
class ReceivingAutoCloseTest {

    private val base = 1_700_000_000_000L

    // ───────────── 选项与默认值 ─────────────

    @Test
    fun `选项为关闭 5 10 30 且默认为关闭`() {
        assertEquals(listOf(0, 5, 10, 30), ReceivingAutoClose.MINUTES_OPTIONS)
        assertEquals(0, ReceivingAutoClose.DEFAULT_MINUTES)
        assertEquals("关闭", ReceivingAutoClose.label(ReceivingAutoClose.OFF))
        assertEquals("10 分钟", ReceivingAutoClose.label(10))
    }

    @Test
    fun `非法时长回落到关闭`() {
        // fail-closed：坏值不能让服务一直挂着
        assertEquals(ReceivingAutoClose.OFF, ReceivingAutoClose.normalize(7))
        assertEquals(ReceivingAutoClose.OFF, ReceivingAutoClose.normalize(-5))
        assertEquals(0L, ReceivingAutoClose.deadlineMs(base, 7))
        assertEquals(30, ReceivingAutoClose.normalize(30))
    }

    // ───────────── 到点停止 ─────────────

    @Test
    fun `到点停止：到期前一毫秒不停，到期即停`() {
        val deadline = ReceivingAutoClose.deadlineMs(base, 5)
        assertEquals(base + 5 * 60_000L, deadline)
        assertFalse(ReceivingAutoClose.shouldStop(deadline, deadline - 1))
        assertTrue(ReceivingAutoClose.shouldStop(deadline, deadline))
        assertTrue(ReceivingAutoClose.shouldStop(deadline, deadline + 1))
        // 到期后剩余恒为 0，不会出现负时长文案
        assertEquals(0L, ReceivingAutoClose.remainingMs(deadline, deadline + 999_999L))
    }

    @Test
    fun `状态机：启动成功后计时并在到点返回该停`() {
        val countdown = AutoCloseCountdown { base }
        assertFalse(countdown.isActive())
        countdown.start(minutes = 10)
        assertTrue(countdown.isActive())
        assertEquals(base + 10 * 60_000L, countdown.deadlineMs())
        assertFalse(countdown.shouldStop(nowMs = base + 9 * 60_000L))
        assertTrue(countdown.shouldStop(nowMs = base + 10 * 60_000L))
    }

    // ───────────── 取消 / 重新开启 ─────────────

    @Test
    fun `手动关闭取消计时且不再判定该停`() {
        val countdown = AutoCloseCountdown { base }
        countdown.start(minutes = 5)
        countdown.cancel()
        assertFalse(countdown.isActive())
        assertEquals(0L, countdown.deadlineMs())
        assertEquals(0L, countdown.remainingMs(nowMs = base + 5 * 60_000L))
        assertFalse(countdown.shouldStop(nowMs = base + 60 * 60_000L))
        assertNull(countdown.remainingText(nowMs = base))
    }

    @Test
    fun `重新开启接收按新起点重新计时`() {
        val countdown = AutoCloseCountdown { base }
        countdown.start(minutes = 5)
        val first = countdown.deadlineMs()
        countdown.cancel()
        // 半小时后重新开启：到期时刻必须从新的起点起算，而不是沿用旧值
        val reopenedAt = base + 30 * 60_000L
        countdown.start(nowMs = reopenedAt, minutes = 5)
        assertEquals(reopenedAt + 5 * 60_000L, countdown.deadlineMs())
        assertTrue(countdown.deadlineMs() > first)
        assertEquals(5 * 60_000L, countdown.remainingMs(nowMs = reopenedAt))
    }

    @Test
    fun `设置改为关闭时等价于取消`() {
        val countdown = AutoCloseCountdown { base }
        countdown.start(minutes = 30)
        countdown.start(nowMs = base, minutes = ReceivingAutoClose.OFF)
        assertFalse(countdown.isActive())
        assertEquals(0L, countdown.deadlineMs())
    }

    @Test
    fun `进程重启后不恢复计时`() {
        // deadline 只在内存：新建实例（= 进程重启）后必须从「未计时」开始
        val before = AutoCloseCountdown { base }
        before.start(minutes = 10)
        val afterRestart = AutoCloseCountdown { base + 60_000L }
        assertFalse(afterRestart.isActive())
        assertEquals(0L, afterRestart.deadlineMs())
        assertFalse(afterRestart.shouldStop(nowMs = base + 24 * 60 * 60_000L))
    }

    // ───────────── 文案与警示色 ─────────────

    @Test
    fun `剩余时间文案边界：60 秒与 0 秒`() {
        assertEquals("1 分钟后自动关闭", ReceivingAutoClose.remainingText(60_000L))
        assertEquals("即将自动关闭", ReceivingAutoClose.remainingText(0L))
        assertTrue(ReceivingAutoClose.isWarning(60_000L))
        assertTrue(ReceivingAutoClose.isWarning(0L))
        assertFalse(ReceivingAutoClose.isWarning(60_001L))
    }

    @Test
    fun `剩余时间文案：分钟向上取整与秒级倒计时`() {
        assertEquals("4 分钟后自动关闭", ReceivingAutoClose.remainingText(240_000L))
        // 3 分 59 秒仍显示 4 分钟（向上取整，避免「看着剩 0 分钟却还没关」）
        assertEquals("4 分钟后自动关闭", ReceivingAutoClose.remainingText(239_000L))
        assertEquals("59 秒后自动关闭", ReceivingAutoClose.remainingText(58_400L))
        assertEquals("1 秒后自动关闭", ReceivingAutoClose.remainingText(1L))
        // 负数按已到点处理，不产生负文案
        assertEquals("即将自动关闭", ReceivingAutoClose.remainingText(-1_000L))
    }

    @Test
    fun `状态机文案：未计时返回 null 计时中返回剩余文案`() {
        val countdown = AutoCloseCountdown { base }
        assertNull(countdown.remainingText())
        countdown.start(minutes = 10)
        assertEquals("10 分钟后自动关闭", countdown.remainingText(nowMs = base))
        assertEquals("10 分钟后自动关闭", countdown.remainingText(nowMs = base + 1_000L))
        assertFalse(countdown.isWarning(nowMs = base))
        assertTrue(countdown.isWarning(nowMs = base + 10 * 60_000L - 30_000L))
    }
}
