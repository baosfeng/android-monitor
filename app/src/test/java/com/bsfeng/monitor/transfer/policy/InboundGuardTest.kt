package com.bsfeng.monitor.transfer.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接收侧入站限流上限（纯 JVM，SEC-04）。
 *
 * 三条闸各自钉住边界：连接数「达到上限即拒」、未确认会话「达到上限即拒」、
 * offer 冷却窗「窗口内第 4 次拒、窗口滑动后放行」。
 */
class InboundGuardTest {

    // ───────── 并发连接 ─────────

    @Test
    fun `并发连接未达上限时放行`() {
        assertTrue(ConnectionBudget.admit(activeConnections = 0))
        assertTrue(ConnectionBudget.admit(activeConnections = InboundLimits.MAX_CONCURRENT_CONNECTIONS - 1))
    }

    @Test
    fun `并发连接达到上限即拒`() {
        assertFalse(ConnectionBudget.admit(activeConnections = InboundLimits.MAX_CONCURRENT_CONNECTIONS))
        assertFalse(ConnectionBudget.admit(activeConnections = InboundLimits.MAX_CONCURRENT_CONNECTIONS + 3))
    }

    @Test
    fun `并发连接上限是 4`() {
        assertEquals(4, InboundLimits.MAX_CONCURRENT_CONNECTIONS)
    }

    // ───────── 未确认会话 ─────────

    @Test
    fun `未确认会话未达上限时可新建`() {
        assertFalse(PendingSessionBudget.shouldReject(pendingSessions = 0))
        assertFalse(PendingSessionBudget.shouldReject(pendingSessions = InboundLimits.MAX_PENDING_SESSIONS - 1))
    }

    @Test
    fun `未确认会话达到上限即拒`() {
        assertTrue(PendingSessionBudget.shouldReject(pendingSessions = InboundLimits.MAX_PENDING_SESSIONS))
    }

    @Test
    fun `未确认会话上限是 5`() {
        assertEquals(5, InboundLimits.MAX_PENDING_SESSIONS)
    }

    // ───────── offer 冷却窗 ─────────

    @Test
    fun `同一来源窗口内第三次放行第四次拒绝`() {
        val limiter = OfferRateLimiter(maxPerWindow = 3, windowMs = 60_000L)
        assertTrue(limiter.allow("10.0.0.2", 1_000L))
        assertTrue(limiter.allow("10.0.0.2", 1_100L))
        assertTrue(limiter.allow("10.0.0.2", 1_200L))
        assertFalse("第 4 次必须拒", limiter.allow("10.0.0.2", 1_300L))
        assertEquals(3, limiter.count("10.0.0.2", 1_300L))
    }

    @Test
    fun `不同来源各算各的额度`() {
        val limiter = OfferRateLimiter(maxPerWindow = 3, windowMs = 60_000L)
        repeat(3) { limiter.allow("10.0.0.2", 1_000L) }
        assertFalse(limiter.allow("10.0.0.2", 1_000L))
        assertTrue("另一个来源不受影响", limiter.allow("10.0.0.3", 1_000L))
    }

    @Test
    fun `窗口滑过之后重新放行`() {
        val limiter = OfferRateLimiter(maxPerWindow = 3, windowMs = 60_000L)
        repeat(3) { limiter.allow("10.0.0.2", 1_000L) }
        assertFalse(limiter.allow("10.0.0.2", 30_000L))
        assertTrue("60 秒前的记录已过期", limiter.allow("10.0.0.2", 61_000L))
    }

    @Test
    fun `被拒的请求不占用后续窗口`() {
        val limiter = OfferRateLimiter(maxPerWindow = 1, windowMs = 1_000L)
        assertTrue(limiter.allow("10.0.0.2", 0L))
        repeat(10) { assertFalse(limiter.allow("10.0.0.2", 100L)) }
        assertEquals("只有第一次计入", 1, limiter.count("10.0.0.2", 100L))
        assertTrue(limiter.allow("10.0.0.2", 1_000L))
    }

    @Test
    fun `来源取空串时等价于同一个来源`() {
        val limiter = OfferRateLimiter(maxPerWindow = 1, windowMs = 60_000L)
        assertTrue(limiter.allow("", 1_000L))
        assertFalse(limiter.allow("", 1_100L))
    }
}
