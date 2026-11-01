package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动定位发令闸：并发互斥、首次成功前的固定退避、全路径最小间隔（静止 180s / 移动 30s）。
 *
 * 三条都对应实测事故：两次主动定位相差 2 秒同时进行（互抢 provider）、INITIAL 每 10 秒发起一次
 * 跑满 timeout 的死循环，以及断档自愈在静止模式下每小时约 40 次满预算主动定位、11 小时只落 8 个点。
 */
class LocationActiveFixGateTest {

    private val now = 1_700_000_000_000L

    // ===== 并发互斥 =====

    @Test
    fun `在飞时第二次发令被拒绝`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.STILL, isInitial = false))
        assertTrue(gate.isInFlight)
        assertFalse(
            "已经有主动定位在飞，第二次必须让路",
            gate.tryBegin(now + 5 * 60_000L, SampleMode.STILL, isInitial = false),
        )
    }

    @Test
    fun `结束后满最小间隔才能再次发令`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.MOVING, isInitial = false))
        gate.finish()
        assertFalse(gate.isInFlight)
        assertFalse("29 秒还在最小间隔内", gate.tryBegin(now + 29_999L, SampleMode.MOVING, isInitial = false))
        assertTrue(gate.tryBegin(now + LocationPace.MOVING_INTERVAL_MS, SampleMode.MOVING, isInitial = false))
    }

    @Test
    fun `在飞判定对非首次阶段同样生效`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.MOVING, isInitial = true))
        // 最小间隔只约束「上一次发起之后」；并发互斥不分阶段
        assertFalse(gate.tryBegin(now + 5 * 60_000L, SampleMode.MOVING, isInitial = false))
    }

    // ===== 全路径最小间隔（B 根因）=====

    @Test
    fun `静止模式最小间隔 180 秒`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.STILL, isInitial = false))
        gate.finish()
        assertFalse(
            "断档自愈 / 心跳每隔几十秒一次，必须被 180 秒挡住",
            gate.tryBegin(now + LocationPace.STILL_INTERVAL_MS - 1, SampleMode.STILL, isInitial = false),
        )
        assertTrue(gate.tryBegin(now + LocationPace.STILL_INTERVAL_MS, SampleMode.STILL, isInitial = false))
    }

    @Test
    fun `移动模式最小间隔 30 秒`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.MOVING, isInitial = false))
        gate.finish()
        assertFalse(gate.tryBegin(now + LocationPace.MOVING_INTERVAL_MS - 1, SampleMode.MOVING, isInitial = false))
        assertTrue(gate.tryBegin(now + LocationPace.MOVING_INTERVAL_MS, SampleMode.MOVING, isInitial = false))
    }

    @Test
    fun `失败也占位 下一次必须等满最小间隔`() {
        val gate = LocationActiveFixGate()
        assertTrue("第一次放行", gate.tryBegin(now, SampleMode.STILL, isInitial = false))
        gate.finish() // 失败出口（回调 null 也走这里）
        assertFalse(
            "失败不占位的话，下一次心跳立刻重试，就是每分钟白烧射频",
            gate.tryBegin(now + 60_000L, SampleMode.STILL, isInitial = false),
        )
        assertEquals(now, gate.lastAttemptAtMs())
        assertTrue(gate.tryBegin(now + LocationPace.STILL_INTERVAL_MS, SampleMode.STILL, isInitial = false))
    }

    @Test
    fun `模式切到移动后按 30 秒放行`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.STILL, isInitial = false))
        gate.finish()
        assertFalse(gate.tryBegin(now + 60_000L, SampleMode.STILL, isInitial = false))
        assertTrue("按当前模式（移动 30s）判，60 秒已过", gate.tryBegin(now + 60_000L, SampleMode.MOVING, isInitial = false))
    }

    @Test
    fun `最小间隔是纯函数 静止 180 秒 移动 30 秒`() {
        assertEquals(180_000L, LocationActiveFixGate.minIntervalMs(SampleMode.STILL, isInitial = false))
        assertEquals(30_000L, LocationActiveFixGate.minIntervalMs(SampleMode.MOVING, isInitial = false))
        // 首次成功前的 30 秒只是下限：静止模式仍按 180 秒
        assertEquals(180_000L, LocationActiveFixGate.minIntervalMs(SampleMode.STILL, isInitial = true))
        assertEquals(30_000L, LocationActiveFixGate.minIntervalMs(SampleMode.MOVING, isInitial = true))
    }

    // ===== 首次成功前的固定退避 =====

    @Test
    fun `首次成功前移动模式 30 秒内不重复发起`() {
        val gate = LocationActiveFixGate()
        assertTrue("第一次必须放行", gate.tryBegin(now, SampleMode.MOVING, isInitial = true))
        gate.finish()
        assertFalse("刚失败 9 秒，还在退避窗口内", gate.tryBegin(now + 9_000L, SampleMode.MOVING, isInitial = true))
        assertFalse(gate.tryBegin(now + 29_999L, SampleMode.MOVING, isInitial = true))
        assertTrue(
            "满 30 秒放行",
            gate.tryBegin(now + LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS, SampleMode.MOVING, isInitial = true),
        )
    }

    @Test
    fun `首次成功前静止模式按 180 秒 不按 30 秒白跑`() {
        // 静止时 INITIAL 的 30 秒只是下限：否则「永远出不来点」会变成每分钟一次满预算
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.STILL, isInitial = true))
        gate.finish()
        assertFalse(
            gate.tryBegin(now + LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS, SampleMode.STILL, isInitial = true),
        )
        assertTrue(gate.tryBegin(now + LocationPace.STILL_INTERVAL_MS, SampleMode.STILL, isInitial = true))
    }

    @Test
    fun `退避是固定值不指数增长`() {
        // 连失败 5 次：间隔始终是 30 秒，不会越退越远（防首次出点饿死）
        val gate = LocationActiveFixGate()
        var cursor = now
        repeat(5) {
            assertTrue("第 " + (it + 1) + " 次发令必须放行", gate.tryBegin(cursor, SampleMode.MOVING, isInitial = true))
            gate.finish()
            assertFalse("刚好 29 秒不放行", gate.tryBegin(cursor + 29_000L, SampleMode.MOVING, isInitial = true))
            cursor += LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS
        }
    }

    @Test
    fun `首次成功之后改按模式间隔约束`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.STILL, isInitial = true))
        gate.finish()
        // 已经不是 INITIAL：仍受全路径最小间隔（静止 180 秒）约束，不再只是 30 秒
        assertFalse(gate.tryBegin(now + 30_000L, SampleMode.STILL, isInitial = false))
        assertTrue(gate.tryBegin(now + LocationPace.STILL_INTERVAL_MS, SampleMode.STILL, isInitial = false))
    }

    // ===== 异常路径 =====

    @Test
    fun `时钟回拨不把最小间隔锁死`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, SampleMode.STILL, isInitial = true))
        gate.finish()
        assertTrue(
            "时间倒退按基线作废处理，否则会锁到时钟追平",
            gate.tryBegin(now - 10 * 60_000L, SampleMode.STILL, isInitial = true),
        )
        assertEquals(now - 10 * 60_000L, gate.lastAttemptAtMs())
    }

    @Test
    fun `并发调用只会有一个拿到发令资格`() {
        val gate = LocationActiveFixGate()
        val wins = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..8).map {
            Thread { if (gate.tryBegin(now, SampleMode.STILL, isInitial = false)) wins.incrementAndGet() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("同时在飞最多一个", 1, wins.get())
    }
}
