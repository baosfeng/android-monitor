package com.bsfeng.monitor.record

// 从 record/LocationCollector.kt 拆出：COORDINATE 落库节流（纯逻辑）。
// 「先看能不能采、采到了才记号」的两步式，一次失败不浪费窗口。

/**
 * COORDINATE 落库节流（纯逻辑）。
 *
 * 用「先看能不能采、采到了才记号」的两步式：一次采集失败（provider 全不可用 / 超时）
 * 不占用 10 分钟窗口，否则一次失败会白白浪费两个心跳周期。
 */
class LocationThrottle(private val minIntervalMs: Long = LocationPolicy.MIN_INTERVAL_MS) {

    private var lastSampleMs: Long? = null

    fun canAcquire(nowMs: Long): Boolean {
        val last = lastSampleMs ?: return true
        return nowMs - last >= minIntervalMs
    }

    fun markAcquired(nowMs: Long) {
        lastSampleMs = nowMs
    }

    fun reset() {
        lastSampleMs = null
    }
}
