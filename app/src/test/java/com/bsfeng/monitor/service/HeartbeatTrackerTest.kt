package com.bsfeng.monitor.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳「内存快照 + 节流落盘」的纯逻辑测试（R10）。
 *
 * 回归点：旧实现每拍至少一次 `prefs.edit{apply}`，轮询每秒一拍、息屏照跑，
 * 整夜都在触发磁盘合并；只为设置页偶尔看一眼心跳。
 */
class HeartbeatTrackerTest {

    /** 可计数的假存储：断言的是「落盘次数」而不是落盘内容。 */
    private class CountingStore : HeartbeatStore {
        var last = 0L
        var lastWrites = 0
        var gapWrites = 0
        var clears = 0

        override fun readLastBeatMs(): Long = last

        override fun writeLastBeatMs(nowMs: Long) {
            last = nowMs
            lastWrites++
        }

        override fun writeGap(gapMs: Long, recoveredAtWallClockMs: Long) {
            gapWrites++
        }

        override fun clearLastBeatMs() {
            last = 0L
            clears++
        }
    }

    @Test
    fun `首拍立即落盘`() {
        val store = CountingStore()
        HeartbeatTracker(store).beat(nowMs = 500L, wallClockMs = 0L)

        assertEquals("进程刚起来就落一次，避免短命进程丢心跳", 1, store.lastWrites)
        assertEquals(500L, store.last)
    }

    @Test
    fun `连续 300 拍落盘不超过 6 次`() {
        val store = CountingStore()
        val tracker = HeartbeatTracker(store)
        // 1Hz × 300 拍 = 5 分钟；每 60 秒落一次 + 首拍 = 不超过 6 次
        repeat(300) { index ->
            tracker.beat(nowMs = 1_000L + index * 1_000L, wallClockMs = index * 1_000L)
        }

        assertTrue("落盘次数应被节流，实际 " + store.lastWrites, store.lastWrites <= 6)
        assertTrue("至少要有首拍那一次", store.lastWrites >= 1)
        assertEquals("没有断层就不该写中断记录", 0, store.gapWrites)
    }

    @Test
    fun `超过阈值立即写中断记录且只写一次`() {
        val store = CountingStore()
        val tracker = HeartbeatTracker(store)
        tracker.beat(nowMs = 1_000L, wallClockMs = 0L)
        tracker.beat(nowMs = 1_000L + HeartbeatWatchdog.STALE_THRESHOLD_MS + 1L, wallClockMs = 2_000_000L)

        assertEquals(1, store.gapWrites)
        assertEquals("中断记录与最新心跳一起落盘", 2, store.lastWrites)
    }

    @Test
    fun `内存快照比落盘值新`() {
        val store = CountingStore()
        val tracker = HeartbeatTracker(store)
        tracker.beat(nowMs = 1_000L, wallClockMs = 0L)
        tracker.beat(nowMs = 2_000L, wallClockMs = 1_000L)

        assertEquals("节流窗口内不落盘", 1, store.lastWrites)
        assertEquals("但进程内读数必须是最新一拍", 2_000L, tracker.currentLastBeatMs())
        assertEquals("落盘值仍是节流前那一拍", 1_000L, store.last)
    }

    @Test
    fun `reset 清空内存与落盘且下一拍重新落盘`() {
        val store = CountingStore()
        val tracker = HeartbeatTracker(store)
        tracker.beat(nowMs = 1_000L, wallClockMs = 0L)
        tracker.reset()

        assertEquals(0L, tracker.currentLastBeatMs())
        assertEquals(1, store.clears)
        val writesAfterReset = store.lastWrites
        tracker.beat(nowMs = 5_000L, wallClockMs = 0L)
        assertEquals("reset 后第一拍必须重新落盘", writesAfterReset + 1, store.lastWrites)
    }
}
