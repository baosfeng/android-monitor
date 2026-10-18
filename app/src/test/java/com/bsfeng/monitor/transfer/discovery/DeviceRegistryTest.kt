package com.bsfeng.monitor.transfer.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceRegistry] / [OfferReplyLimiter] 测试（纯 JVM，时间由用例注入）。
 *
 * 钉住协议 §3 的两条判定：15s 离线、60s 移除；同一 IP 2s 内只回一次 `offer`。
 */
class DeviceRegistryTest {

    private fun announce(deviceId: String, port: Int = 53319, alias: String = "Mac") =
        DiscoveryMessage.announce(deviceId = deviceId, alias = alias, port = port)

    @Test
    fun `同一 deviceId 重复公告刷新 IP 与端口`() {
        val registry = DeviceRegistry()
        registry.onSeen(announce("mac-1", port = 53319), "192.168.1.10", 1_000L)
        val updated = registry.onSeen(announce("mac-1", port = 53320), "192.168.1.11", 2_000L)

        assertEquals(1, registry.size())
        assertEquals("192.168.1.11", updated.hostAddress)
        assertEquals(53320, updated.port)
        assertEquals(2_000L, updated.lastSeenMs)
    }

    @Test
    fun `15 秒未公告标记离线 60 秒后移除`() {
        val registry = DeviceRegistry()
        registry.onSeen(announce("mac-1"), "192.168.1.10", 0L)

        assertTrue(registry.snapshot(14_999L).single().online)
        assertFalse(registry.snapshot(15_000L).single().online)
        assertEquals(1, registry.snapshot(59_999L).size)
        assertEquals(0, registry.snapshot(60_000L).size)
        assertEquals(0, registry.size())
    }

    @Test
    fun `重新公告后恢复在线`() {
        val registry = DeviceRegistry()
        registry.onSeen(announce("mac-1"), "192.168.1.10", 0L)
        assertFalse(registry.snapshot(20_000L).single().online)
        registry.onSeen(announce("mac-1"), "192.168.1.10", 21_000L)
        assertTrue(registry.snapshot(21_000L).single().online)
    }

    @Test
    fun `快照排序 在线优先 同状态按最近出现倒序`() {
        val registry = DeviceRegistry()
        registry.onSeen(announce("online-old"), "192.168.1.2", 14_000L)
        registry.onSeen(announce("online-new"), "192.168.1.3", 19_000L)
        registry.onSeen(announce("offline"), "192.168.1.4", 0L)
        // offline 在 20s 时 age=20s ≥ 15s → 离线；两台在线设备按最近出现倒序
        val snapshot = registry.snapshot(20_000L)

        assertEquals(listOf("online-new", "online-old", "offline"), snapshot.map { it.deviceId })
        assertFalse(snapshot.last().online)
    }

    @Test
    fun `移除与清空`() {
        val registry = DeviceRegistry()
        registry.onSeen(announce("mac-1"), "192.168.1.10", 0L)
        registry.onSeen(announce("mac-2"), "192.168.1.11", 0L)
        assertEquals(2, registry.size())
        assertEquals("mac-1", registry.remove("mac-1")?.deviceId)
        assertNull(registry.find("mac-1"))
        assertEquals(1, registry.size())
        registry.clear()
        assertEquals(0, registry.size())
        assertTrue(registry.snapshot(0L).isEmpty())
    }

    @Test
    fun `阈值非法时构造失败`() {
        assertTrue(
            runCatching { DeviceRegistry(offlineAfterMs = 60_000L, removeAfterMs = 15_000L) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `同一 IP 两秒内只回一次 offer`() {
        val limiter = OfferReplyLimiter(windowMs = 2_000L)
        assertTrue(limiter.allow("192.168.1.10", 0L))
        assertFalse(limiter.allow("192.168.1.10", 1_999L))
        assertTrue(limiter.allow("192.168.1.10", 2_000L))
        // 不同 IP 各自独立
        assertTrue(limiter.allow("192.168.1.11", 2_100L))
    }

    @Test
    fun `节流表按窗口清理`() {
        val limiter = OfferReplyLimiter(windowMs = 2_000L)
        assertTrue(limiter.allow("192.168.1.10", 0L))
        limiter.prune(1_000L)
        assertFalse(limiter.allow("192.168.1.10", 1_500L))
        limiter.prune(2_000L)
        assertTrue(limiter.allow("192.168.1.10", 2_500L))
    }

    @Test
    fun `设备能力判定 声明 recv 才能接收`() {
        val registry = DeviceRegistry()
        registry.onSeen(announce("mac-1"), "192.168.1.10", 0L)
        assertTrue(registry.snapshot(0L).single().canReceive())

        registry.onSeen(
            DiscoveryMessage.announce("mac-2", "Mac", 53319).copy(caps = listOf("send")),
            "192.168.1.11",
            0L,
        )
        assertFalse(registry.find("mac-2")!!.canReceive())
    }
}
