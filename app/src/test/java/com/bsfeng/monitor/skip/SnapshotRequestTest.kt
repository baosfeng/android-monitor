package com.bsfeng.monitor.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 页面快照请求的单测（纯 JVM）。
 *
 * 关注点：到期前不消费、到期只消费一次、取消与默认延迟回落、导出结果记录。
 */
class SnapshotRequestTest {

    @Before
    fun setup() {
        SnapshotRequest.cancel()
        SnapshotRequest.record(null)
    }

    @Test
    fun `到期前不被消费`() {
        SnapshotRequest.request(5_000L)
        val now = System.currentTimeMillis() + 100L
        assertFalse(SnapshotRequest.consumeIfDue(now))
        assertTrue(SnapshotRequest.isPending())
    }

    @Test
    fun `到期后被消费且只消费一次`() {
        SnapshotRequest.request(1_000L)
        val due = System.currentTimeMillis() + 2_000L
        assertTrue(SnapshotRequest.consumeIfDue(due))
        assertFalse("同一请求只能被消费一次", SnapshotRequest.consumeIfDue(due))
        assertFalse(SnapshotRequest.isPending())
    }

    @Test
    fun `未请求时消费返回假`() {
        assertFalse(SnapshotRequest.consumeIfDue(Long.MAX_VALUE))
    }

    @Test
    fun `cancel 取消待处理请求`() {
        SnapshotRequest.request(1_000L)
        SnapshotRequest.cancel()
        assertFalse(SnapshotRequest.isPending())
        assertFalse(SnapshotRequest.consumeIfDue(System.currentTimeMillis() + 5_000L))
    }

    @Test
    fun `非正延迟回落为默认延迟`() {
        SnapshotRequest.request(0L)
        assertTrue("0 延迟应回落为默认值，仍处于待处理", SnapshotRequest.isPending())
        assertFalse("刚请求时不应立即到期", SnapshotRequest.consumeIfDue(System.currentTimeMillis()))
    }

    @Test
    fun `记录导出结果`() {
        assertNull(SnapshotRequest.lastPath)
        SnapshotRequest.record("/data/user/0/com.bsfeng.monitor/files/snapshots/1.json")
        assertEquals("/data/user/0/com.bsfeng.monitor/files/snapshots/1.json", SnapshotRequest.lastPath)
    }

    // ==================== 切到目标 App 后导出（推荐用法） ====================

    @Test
    fun `切走之后才开始倒计时`() {
        val origin = "com.bsfeng.monitor"
        SnapshotRequest.requestOnAppSwitch(origin, delayMs = 2_500L)
        val t0 = 1_000_000L

        // 仍停留在发起请求的 App：不安排导出
        SnapshotRequest.onForegroundPackage(origin, t0)
        assertFalse("没切走时不应到期", SnapshotRequest.consumeIfDue(t0 + 60_000L))
        assertTrue("应仍处于待处理", SnapshotRequest.isPending())

        // 切到目标 App：从这一刻起算 2.5 秒
        SnapshotRequest.onForegroundPackage("com.qq.reader", t0)
        assertFalse("切过去但未到 2.5 秒", SnapshotRequest.consumeIfDue(t0 + 2_000L))
        assertTrue("切过去 2.5 秒后应到期", SnapshotRequest.consumeIfDue(t0 + 2_500L))
    }

    @Test
    fun `切到目标 App 后只安排一次导出`() {
        SnapshotRequest.requestOnAppSwitch("com.bsfeng.monitor", delayMs = 1_000L)
        SnapshotRequest.onForegroundPackage("com.qq.reader", 0L)
        assertTrue(SnapshotRequest.consumeIfDue(1_000L))
        // 目标 App 内继续变化的包名不再触发新的导出
        SnapshotRequest.onForegroundPackage("com.qq.reader", 2_000L)
        assertFalse("同一请求只能导出一次", SnapshotRequest.consumeIfDue(9_999_999L))
    }

    @Test
    fun `非正延时回落为默认切换等待时间`() {
        SnapshotRequest.requestOnAppSwitch("com.bsfeng.monitor", delayMs = 0L)
        SnapshotRequest.onForegroundPackage("com.other.app", 0L)
        assertFalse(SnapshotRequest.consumeIfDue(SnapshotRequest.SWITCH_SETTLE_MS - 1L))
        assertTrue(SnapshotRequest.consumeIfDue(SnapshotRequest.SWITCH_SETTLE_MS))
    }

    @Test
    fun `cancel 同时取消等待切换的请求`() {
        SnapshotRequest.requestOnAppSwitch("com.bsfeng.monitor", delayMs = 1_000L)
        SnapshotRequest.cancel()
        assertFalse(SnapshotRequest.isPending())
        SnapshotRequest.onForegroundPackage("com.qq.reader", 0L)
        assertFalse("取消后切 App 也不应导出", SnapshotRequest.consumeIfDue(9_999_999L))
    }
}