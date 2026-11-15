package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新记账本（锁 / 节流 / 退避）按**实例键**隔离的回归。
 *
 * 盯住的失败形态最容易被忽略：键还是类型 id 时，同类型第二个账号会被第一个账号的
 * 节流与 429 退避挡住 —— 表现是「加了账号但数字永远不动」，不报错、不崩、只是不刷新。
 */
class QuotaRefreshBookkeepingTest {

    private var now = 1_000L
    private fun bookkeeping() = QuotaRefreshBookkeeping { now }

    @Test
    fun `默认允许刷新`() {
        val b = bookkeeping()
        assertTrue(b.allows("ollama", intervalSeconds = 60))
    }

    @Test
    fun `节流窗口内同一实例不许再刷`() {
        val b = bookkeeping()
        b.markRefreshed("ollama")
        now += 30
        assertFalse(b.allows("ollama", intervalSeconds = 60))
        now += 31
        assertTrue(b.allows("ollama", intervalSeconds = 60))
    }

    @Test
    fun `同类型第二个实例不受第一个实例的节流影响`() {
        val b = bookkeeping()
        b.markRefreshed("ollama")
        assertFalse(b.allows("ollama", intervalSeconds = 60))
        // 关键断言：换实例键就该放行，否则「加了账号但数字不动」
        assertTrue(b.allows("ollama#2", intervalSeconds = 60))
    }

    @Test
    fun `429 退避按实例隔离`() {
        val b = bookkeeping()
        b.backoff("ollama", QuotaError(QuotaErrorKind.RATE_LIMITED, retryAfterSeconds = 120))
        now += 60
        assertFalse(b.allows("ollama", intervalSeconds = 0))
        assertTrue(b.allows("ollama#2", intervalSeconds = 0))
        now += 61
        assertTrue(b.allows("ollama", intervalSeconds = 0))
    }

    @Test
    fun `非限流错误不退避`() {
        val b = bookkeeping()
        b.backoff("ollama", QuotaError(QuotaErrorKind.TRANSPORT))
        assertTrue(b.allows("ollama", intervalSeconds = 0))
    }

    @Test
    fun `没给 Retry-After 时退默认一分钟`() {
        val b = bookkeeping()
        b.backoff("ollama", QuotaError(QuotaErrorKind.RATE_LIMITED))
        now += 59
        assertFalse(b.allows("ollama", intervalSeconds = 0))
        now += 2
        assertTrue(b.allows("ollama", intervalSeconds = 0))
    }

    @Test
    fun `清除后立刻可查`() {
        val b = bookkeeping()
        b.markRefreshed("ollama")
        b.backoff("ollama", QuotaError(QuotaErrorKind.RATE_LIMITED, retryAfterSeconds = 600))
        assertFalse(b.allows("ollama", intervalSeconds = 60))
        b.forget("ollama")
        // 重新填 Key 的人不该再等上一家的冷却窗口
        assertTrue(b.allows("ollama", intervalSeconds = 0))
    }

    @Test
    fun `不同实例拿到不同的锁`() {
        val b = bookkeeping()
        assertEquals(b.lockOf("ollama"), b.lockOf("ollama"))
        assertTrue(b.lockOf("ollama") !== b.lockOf("ollama#2"))
    }
}
