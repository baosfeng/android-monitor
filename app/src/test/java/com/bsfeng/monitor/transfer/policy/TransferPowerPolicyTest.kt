package com.bsfeng.monitor.transfer.policy

import com.bsfeng.monitor.transfer.discovery.DiscoveryCadence
import com.bsfeng.monitor.transfer.service.ServiceModePolicy
import com.bsfeng.monitor.transfer.service.TransferTileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 省电 / 磁贴 / 心跳降频的纯函数测试（纯 JVM）。
 *
 * 钉住三条核心口径：
 * 1. 「后台接收」关闭时服务不常驻（App 不可见 → 不运行）；
 * 2. 磁贴状态映射（运行中 ACTIVE、停止 INACTIVE、副标题显示在线设备数）；
 * 3. UDP 心跳降频阈值（有设备 5s；连续 60s 无设备降到 15s）。
 */
class TransferPowerPolicyTest {

    // ───────────── 服务常驻策略 ─────────────

    @Test
    fun `后台接收关闭时服务只在 App 可见时运行`() {
        assertFalse(ServiceModePolicy.shouldRunService(backgroundReceive = false, appVisible = false))
        assertTrue(ServiceModePolicy.shouldRunService(backgroundReceive = false, appVisible = true))
    }

    @Test
    fun `后台接收开启时服务常驻`() {
        assertTrue(ServiceModePolicy.shouldRunService(backgroundReceive = true, appVisible = false))
        assertTrue(ServiceModePolicy.shouldRunService(backgroundReceive = true, appVisible = true))
    }

    @Test
    fun `只有常驻模式进前台挂通知`() {
        assertFalse(ServiceModePolicy.shouldRunForeground(backgroundReceive = false))
        assertTrue(ServiceModePolicy.shouldRunForeground(backgroundReceive = true))
        assertEquals(ServiceModePolicy.Mode.APP_ONLY, ServiceModePolicy.mode(false))
        assertEquals(ServiceModePolicy.Mode.PERSISTENT, ServiceModePolicy.mode(true))
    }

    @Test
    fun `发现线程在后台关闭且不可见时停止`() {
        // 可被发现开关关掉后，两种模式下都不跑
        assertFalse(
            ServiceModePolicy.shouldRunDiscovery(
                backgroundReceive = true, appVisible = true, discoverable = false,
            ),
        )
        // 后台接收关闭 + App 不可见 → 停（要求 3：组播接收线程此时停止）
        assertFalse(
            ServiceModePolicy.shouldRunDiscovery(
                backgroundReceive = false, appVisible = false, discoverable = true,
            ),
        )
        assertTrue(
            ServiceModePolicy.shouldRunDiscovery(
                backgroundReceive = false, appVisible = true, discoverable = true,
            ),
        )
        assertTrue(
            ServiceModePolicy.shouldRunDiscovery(
                backgroundReceive = true, appVisible = false, discoverable = true,
            ),
        )
    }

    @Test
    fun `模式提示文案`() {
        assertEquals("仅应用内可见 · 后台接收已关闭", ServiceModePolicy.modeHint(false))
        assertEquals("后台接收已开启 · 常驻通知", ServiceModePolicy.modeHint(true))
    }

    // ───────────── 磁贴状态 ─────────────

    @Test
    fun `磁贴运行中为 ACTIVE 并显示设备数`() {
        val snapshot = TransferTileState.of(running = true, onlineDevices = 3)
        assertTrue(snapshot.active)
        assertEquals("局域网互传", snapshot.label)
        assertEquals("3 台设备", snapshot.subtitle)
    }

    @Test
    fun `磁贴停止为 INACTIVE 且副标题为已关闭`() {
        val snapshot = TransferTileState.of(running = false, onlineDevices = 3)
        assertFalse(snapshot.active)
        assertEquals("已关闭", snapshot.subtitle)
    }

    @Test
    fun `磁贴设备数为负按 0 处理`() {
        assertEquals("0 台设备", TransferTileState.of(running = true, onlineDevices = -2).subtitle)
        assertEquals("0 台设备", TransferTileState.of(running = true, onlineDevices = 0).subtitle)
    }

    // ───────────── 心跳降频 ─────────────

    @Test
    fun `有在线设备时保持 5 秒心跳`() {
        val now = 10_000_000L
        // 即使已经很久没有设备，只要有设备就回到活跃节奏
        assertEquals(
            DiscoveryCadence.ACTIVE_INTERVAL_MS,
            DiscoveryCadence.intervalMs(onlineCount = 1, noDeviceSinceMs = now - 600_000L, nowMs = now),
        )
    }

    @Test
    fun `连续无设备不足 60 秒仍按 5 秒`() {
        val now = 10_000_000L
        assertEquals(
            DiscoveryCadence.ACTIVE_INTERVAL_MS,
            DiscoveryCadence.intervalMs(onlineCount = 0, noDeviceSinceMs = now - 59_999L, nowMs = now),
        )
    }

    @Test
    fun `连续无设备满 60 秒降到 15 秒`() {
        val now = 10_000_000L
        assertEquals(
            DiscoveryCadence.IDLE_INTERVAL_MS,
            DiscoveryCadence.intervalMs(onlineCount = 0, noDeviceSinceMs = now - 60_000L, nowMs = now),
        )
        assertEquals(
            DiscoveryCadence.IDLE_INTERVAL_MS,
            DiscoveryCadence.intervalMs(onlineCount = 0, noDeviceSinceMs = now - 600_000L, nowMs = now),
        )
    }

    @Test
    fun `没有无设备基线时先按活跃节奏`() {
        assertEquals(
            DiscoveryCadence.ACTIVE_INTERVAL_MS,
            DiscoveryCadence.intervalMs(onlineCount = 0, noDeviceSinceMs = 0L, nowMs = 10_000_000L),
        )
    }
}
