package com.bsfeng.monitor.transfer.discovery

/**
 * UDP 心跳节奏（省电）：有在线设备保持 5s；连续 [IDLE_AFTER_MS] 没有在线设备后降到 15s。
 *
 * 纯函数，可直接单测（见 `DiscoveryCadenceTest`）。发现线程每轮按本函数取下一次间隔。
 */
object DiscoveryCadence {

    /** 活跃节奏（有设备 / 刚离线不久）。 */
    const val ACTIVE_INTERVAL_MS: Long = 5_000L

    /** 空闲节奏（长时间无设备）。 */
    const val IDLE_INTERVAL_MS: Long = 15_000L

    /** 连续无在线设备多久后降频。 */
    const val IDLE_AFTER_MS: Long = 60_000L

    /**
     * 下一次公告间隔。
     *
     * @param onlineCount 当前在线设备数。
     * @param noDeviceSinceMs 本轮「开始无设备」的时刻；`<= 0` 表示还没有基线（先按活跃节奏）。
     * @param nowMs 当前时刻。
     */
    fun intervalMs(onlineCount: Int, noDeviceSinceMs: Long, nowMs: Long): Long {
        if (onlineCount > 0) return ACTIVE_INTERVAL_MS
        if (noDeviceSinceMs <= 0L) return ACTIVE_INTERVAL_MS
        return if (nowMs - noDeviceSinceMs >= IDLE_AFTER_MS) IDLE_INTERVAL_MS else ACTIVE_INTERVAL_MS
    }
}
