package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 ticker 排程：SLEEPING 的慢拍子（5 分钟）与「传感器把门禁拉到 PROBING / MOVING」是两条独立回路，
 * 状态迁移必须立刻重排，否则探测与静默补采会被拖到 5 分钟后 —— 这是时序正确性，必须单测钉死。
 */
class LocationTickerSchedulerTest {

    private val start = 1_700_000_000_000L

    private class FakeHost : TickerHost {
        var pendingDelayMs: Long? = null
        val delays = mutableListOf<Long>()
        var cancels = 0

        override fun schedule(delayMs: Long) {
            delays += delayMs
            pendingDelayMs = delayMs
        }

        override fun cancel() {
            cancels++
            pendingDelayMs = null
        }
    }

    private var now = start
    private var gateState = MotionGate.State.SLEEPING
    private var lastFallbackAtMs = 0L
    private val host = FakeHost()

    private fun newScheduler() = LocationTickerScheduler(
        host = host,
        clock = { now },
        stateOf = { gateState },
        lastFallbackAtMsOf = { lastFallbackAtMs },
    )

    @Test
    fun `启动时按当前态排第一拍 SLEEPING 是 5 分钟`() {
        val scheduler = newScheduler()
        scheduler.start()
        assertEquals(300_000L, host.pendingDelayMs)
        assertTrue(scheduler.isActive)
    }

    @Test
    fun `状态迁移立刻重排 探测不再等慢拍子`() {
        val scheduler = newScheduler()
        scheduler.start()
        assertEquals(300_000L, host.pendingDelayMs)

        // 传感器把门禁拉到 PROBING：必须立刻改成 30 秒一拍
        gateState = MotionGate.State.PROBING
        scheduler.onStateChanged()
        assertEquals("状态迁移必须立刻恢复 30 秒节奏", 30_000L, host.pendingDelayMs)
        assertEquals(listOf(300_000L, 30_000L), host.delays)

        // 再升级 MOVING：仍是 30 秒
        gateState = MotionGate.State.MOVING
        scheduler.onStateChanged()
        assertEquals(30_000L, host.pendingDelayMs)
    }

    @Test
    fun `tick 期间被重排过就不再重复排 避免双 tick 链`() {
        val scheduler = newScheduler()
        scheduler.start()
        val versionAtTickStart = scheduler.version

        // 模拟「tick 里发生了状态迁移」：业务重排了一拍
        gateState = MotionGate.State.PROBING
        scheduler.onStateChanged()
        assertEquals(2, host.delays.size)

        scheduler.afterTick(versionAtTickStart)
        assertEquals("期间已重排，tick 结束不能再排一次", 2, host.delays.size)
    }

    @Test
    fun `tick 没有触发迁移时按当前态续排`() {
        val scheduler = newScheduler()
        scheduler.start()
        val versionAtTickStart = scheduler.version

        scheduler.afterTick(versionAtTickStart)
        assertEquals(2, host.delays.size)
        assertEquals(300_000L, host.pendingDelayMs)
    }

    @Test
    fun `兜底时刻初始化后拍子按剩余时间收紧`() {
        val scheduler = newScheduler()
        lastFallbackAtMs = start - (LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS - 40_000L)
        scheduler.start()
        assertEquals("距兜底 40 秒就排 40 秒，兜底准点", 40_000L, host.pendingDelayMs)
    }

    @Test
    fun `stop 之后不再排程`() {
        val scheduler = newScheduler()
        scheduler.start()
        scheduler.stop()
        assertFalse(scheduler.isActive)
        assertEquals(1, host.cancels)

        gateState = MotionGate.State.PROBING
        scheduler.onStateChanged()
        scheduler.afterTick(0L)
        assertEquals("停止后不能再排任何一拍", 1, host.delays.size)
    }

    @Test
    fun `重复 start 不会排出两拍`() {
        val scheduler = newScheduler()
        scheduler.start()
        scheduler.start()
        assertEquals(1, host.delays.size)
    }
}
