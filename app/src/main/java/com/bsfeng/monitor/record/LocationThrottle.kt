package com.bsfeng.monitor.record

// 从 record/LocationCollector.kt 拆出：COORDINATE 落库节流（纯逻辑）。
// 「先看能不能采、采到了才记号」的两步式，一次失败不浪费窗口。

/**
 * COORDINATE 落库节流（纯逻辑）。
 *
 * 用「先看能不能采、采到了才记号」的两步式：一次采集失败（provider 全不可用 / 超时）
 * 不占用窗口，否则一次失败会白白浪费一整个采样间隔。
 *
 * 间隔**按模式传入**（移动 30 秒 / 静止 3 分钟），不再固定 10 分钟；
 * 默认值取静止间隔，调用方漏传时行为最保守（最省电）。
 */
class LocationThrottle(private val defaultIntervalMs: Long = LocationPace.STILL_INTERVAL_MS) {

    @Volatile
    private var lastSampleMs: Long? = null

    fun canAcquire(nowMs: Long, intervalMs: Long = defaultIntervalMs): Boolean {
        val last = lastSampleMs ?: return true
        return nowMs - last >= intervalMs
    }

    fun markAcquired(nowMs: Long) {
        lastSampleMs = nowMs
    }

    fun reset() {
        lastSampleMs = null
    }

    /** 上次落库时刻（无则 null）；定时器判断「是否该采」与诊断日志用。 */
    fun lastSampleMs(): Long? = lastSampleMs
}
