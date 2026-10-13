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
}
