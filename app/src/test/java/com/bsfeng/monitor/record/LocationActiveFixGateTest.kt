package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动定位发令闸：并发互斥（A3）与首次成功前的固定退避（A2）。
 *
 * 两条都对应实测事故：两次主动定位相差 2 秒同时进行（互抢 provider），以及 INITIAL 每 10 秒
 * 发起一次、每次都跑满 timeout 的死循环。
 */
class LocationActiveFixGateTest {

    private val now = 1_700_000_000_000L

    // ===== A3 并发互斥 =====

    @Test
    fun `在飞时第二次发令被拒绝`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, isInitial = false))
        assertTrue(gate.isInFlight)
        assertFalse("已经有主动定位在飞，第二次必须让路", gate.tryBegin(now + 1_000L, isInitial = false))
    }

    @Test
    fun `结束后可以再次发令`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, isInitial = false))
        gate.finish()
        assertFalse(gate.isInFlight)
        assertTrue(gate.tryBegin(now + 1_000L, isInitial = false))
    }

    @Test
    fun `在飞判定对非首次阶段同样生效`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, isInitial = true))
        // 退避只约束 INITIAL；并发互斥不分阶段
        assertFalse(gate.tryBegin(now + 5 * 60_000L, isInitial = false))
    }

    // ===== A2 首次成功前的固定退避 =====

    @Test
    fun `首次成功前 30 秒内不重复发起`() {
        val gate = LocationActiveFixGate()
        assertTrue("第一次必须放行", gate.tryBegin(now, isInitial = true))
        gate.finish()
        assertFalse("刚失败 9 秒，还在退避窗口内", gate.tryBegin(now + 9_000L, isInitial = true))
        assertFalse(gate.tryBegin(now + 29_999L, isInitial = true))
        assertTrue("满 30 秒放行", gate.tryBegin(now + LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS, isInitial = true))
    }

    @Test
    fun `退避是固定值不指数增长`() {
        // 连失败 5 次：间隔始终是 30 秒，不会越退越远（防首次出点饿死）
        val gate = LocationActiveFixGate()
        var cursor = now
        repeat(5) {
            assertTrue("第 " + (it + 1) + " 次发令必须放行", gate.tryBegin(cursor, isInitial = true))
            gate.finish()
            assertFalse("刚好 29 秒不放行", gate.tryBegin(cursor + 29_000L, isInitial = true))
            cursor += LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS
        }
    }

    @Test
    fun `首次成功之后不受退避约束`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, isInitial = true))
        gate.finish()
        // 已经不是 INITIAL：由模式节流与断档自愈管，闸不再叠加退避
        assertTrue(gate.tryBegin(now + 1_000L, isInitial = false))
    }

    @Test
    fun `reset 后立刻可以重新发令`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, isInitial = true))
        gate.reset()
        assertFalse(gate.isInFlight)
        assertNull(gate.lastAttemptAtMs())
        assertTrue(gate.tryBegin(now, isInitial = true))
    }

    // ===== 异常路径 =====

    @Test
    fun `时钟回拨不把退避锁死`() {
        val gate = LocationActiveFixGate()
        assertTrue(gate.tryBegin(now, isInitial = true))
        gate.finish()
        assertTrue("时间倒退按基线作废处理，否则会锁到时钟追平", gate.tryBegin(now - 10 * 60_000L, isInitial = true))
        assertEquals(now - 10 * 60_000L, gate.lastAttemptAtMs())
    }

    @Test
    fun `并发调用只会有一个拿到发令资格`() {
        val gate = LocationActiveFixGate()
        val wins = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..8).map {
            Thread { if (gate.tryBegin(now, isInitial = false)) wins.incrementAndGet() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("同时在飞最多一个", 1, wins.get())
    }
}
