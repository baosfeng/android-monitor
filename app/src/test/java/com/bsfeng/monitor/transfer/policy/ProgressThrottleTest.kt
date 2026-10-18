package com.bsfeng.monitor.transfer.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度节流 + 速度口径的纯 JVM 测试（无 Robolectric）。
 *
 * 钉住真机 Bug 的修复口径：100MB 上传 27.8s 内约 1600 次进度回调 → 界面白屏；
 * 节流后 UI 每秒最多感知 6~7 次，且终态 / 状态跃迁不经过节流。
 */
class ProgressThrottleTest {

    @Test
    fun `首块进度立即发射`() {
        assertTrue(ProgressThrottle.shouldEmit(ProgressThrottle.NO_EMIT, nowMs = 1_000L))
    }

    @Test
    fun `窗口内连续多次进度只发射一次`() {
        var lastEmit = ProgressThrottle.NO_EMIT
        val emits = mutableListOf<Long>()
        // 模拟 27.8s / 1600 次回调中的一段：每 17ms 一次（≈57 次每秒）
        var now = 10_000L
        repeat(200) {
            if (ProgressThrottle.shouldEmit(lastEmit, now)) {
                lastEmit = ProgressThrottle.nextEmitMs(lastEmit, now)
                emits += now
            }
            now += 17L
        }
        // 200 × 17ms ≈ 3400ms；150ms 窗口 → 约 23 次，远少于 200 次
        assertTrue("发射次数应被压到 150ms 一次，实际=${emits.size}", emits.size in 21..24)
        // 相邻发射间隔都不小于窗口
        emits.zipWithNext().forEach { (a, b) -> assertTrue("间隔 $b-$a 不应小于窗口", b - a >= 150L) }
    }

    @Test
    fun `窗口外正常发射`() {
        val last = 10_000L
        assertFalse(ProgressThrottle.shouldEmit(last, nowMs = last + 149L))
        assertTrue(ProgressThrottle.shouldEmit(last, nowMs = last + 150L))
        assertTrue(ProgressThrottle.shouldEmit(last, nowMs = last + 1_000L))
    }

    @Test
    fun `nextEmitMs 只在发射时推进状态`() {
        val last = 10_000L
        assertEquals(last, ProgressThrottle.nextEmitMs(last, nowMs = last + 10L))
        assertEquals(last + 150L, ProgressThrottle.nextEmitMs(last, nowMs = last + 150L))
    }

    @Test
    fun `窗口可注入`() {
        assertFalse(ProgressThrottle.shouldEmit(0L, nowMs = 99L, windowMs = 200L))
        assertTrue(ProgressThrottle.shouldEmit(0L, nowMs = 200L, windowMs = 200L))
    }

    // ───────────── 速度：节流后口径不变 ─────────────

    @Test
    fun `首次采样直接取瞬时速度`() {
        // 150ms 内传了 3MB → 20MB/s
        val bps = ProgressSpeed.bytesPerSecond(
            previousReceived = 0L,
            previousBps = 0L,
            previousAtMs = 1_000L,
            received = 3_000_000L,
            nowMs = 1_150L,
        )
        assertEquals(20_000_000L, bps)
    }

    @Test
    fun `节流窗口内的采样速度不会变成 0`() {
        // 慢速文件：150ms 只传了 64KiB（≈437KB/s），仍应给出非 0 速度
        val bps = ProgressSpeed.bytesPerSecond(
            previousReceived = 0L,
            previousBps = 0L,
            previousAtMs = 1_000L,
            received = 65_536L,
            nowMs = 1_150L,
        )
        assertTrue("慢速也必须有非 0 速度，实际=$bps", bps > 0L)
    }

    @Test
    fun `速度做 3比1 指数平滑不会跳变`() {
        val smoothed = ProgressSpeed.bytesPerSecond(
            previousReceived = 1_000_000L,
            previousBps = 10_000_000L,
            previousAtMs = 1_000L,
            received = 2_000_000L, // 150ms 传了 1MB ≈ 6.6MB/s
            nowMs = 1_150L,
        )
        assertEquals(9_166_666L, smoothed)
        assertTrue("平滑后应落在两次采样之间", smoothed in 6_666_666L..10_000_000L)
    }

    @Test
    fun `时钟回拨或没有新增字节时保持上次速度`() {
        assertEquals(
            7_000L,
            ProgressSpeed.bytesPerSecond(1_000L, 7_000L, 2_000L, 1_000L, 2_000L),
        )
        assertEquals(
            7_000L,
            ProgressSpeed.bytesPerSecond(1_000L, 7_000L, 2_000L, 1_000L, 1_500L),
        )
    }
}
