package com.bsfeng.monitor.autobook

/**
 * 无障碍事件节流（纯逻辑，可 JVM 单测）。
 *
 * 真机教训（2026-10-17，微信转账）：指纹页在 16:06:10.405 被解析，支付成功页的
 * WINDOW_STATE_CHANGED 在 16:06:10.807 到达（**402ms 后**），被 800ms 节流直接丢掉；
 * 而结果页是静态页面、之后不再有 CONTENT_CHANGED —— 那一笔永远不弹窗。
 *
 * 因此：
 * - **WINDOW_STATE_CHANGED**（页面切换，低频且关键）永远放行，并重置节流窗口；
 * - 只有 **CONTENT_CHANGED**（高频，支付页会连抛多个）才走 800ms 窗口。
 */
internal class AutoBookEventThrottle(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    /** 上次真正处理事件的时间；null 表示还没处理过。 */
    private var lastRunAtMs: Long? = null

    /**
     * @param isWindowStateChange 事件是否是 TYPE_WINDOW_STATE_CHANGED
     * @param nowMs 当前时间
     * @return true = 交给解析器；false = 本次跳过
     */
    fun shouldProcess(isWindowStateChange: Boolean, nowMs: Long): Boolean {
        if (isWindowStateChange) {
            lastRunAtMs = nowMs
            return true
        }
        val last = lastRunAtMs
        if (last != null && nowMs - last < windowMs) return false
        lastRunAtMs = nowMs
        return true
    }

    companion object {
        /** 内容变化节流窗口：支付结果页会连续抛出多个 CONTENT_CHANGED。 */
        const val DEFAULT_WINDOW_MS = 800L
    }
}
