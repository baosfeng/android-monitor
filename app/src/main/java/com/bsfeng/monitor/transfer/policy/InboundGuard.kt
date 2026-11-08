package com.bsfeng.monitor.transfer.policy

/**
 * 接收侧入站限流（纯逻辑，无 Android 依赖，可纯 JVM 单测）。
 *
 * 背景（SEC-04）：局域网上任何人都能对 `/offer` 发请求，原实现每来一条就建会话、写任务表、
 * 弹 PIN；局域网内几十条并发 offer 就能把库和 PIN 弹层打爆。三条闸各自独立：
 *
 * | 闸 | 位置 | 上限 | 超限后果 |
 * |----|------|------|---------|
 * | 并发连接 | [ConnectionBudget] | 4 | 直接关 socket，不读请求 |
 * | 每来源 IP offer | [OfferRateLimiter] | 60 秒 3 次 | 回 4006（不建会话、不写库） |
 * | 未确认会话 | [PendingSessionBudget] | 5 | 回 4006（同上） |
 *
 * 为什么是这三个数：正常使用是「一台对端 + 一次传输 = 1 条连接、1 次 offer」，
 * 4 / 3 / 5 对正常流程都是数倍余量；同时把「单机在 60 秒内能制造的会话数」压到 5 条以内。
 */
object InboundLimits {

    /** 同时服务的连接数上限（含上传中的长连接）。 */
    const val MAX_CONCURRENT_CONNECTIONS: Int = 4

    /** offer 冷却窗（毫秒）。 */
    const val OFFER_WINDOW_MS: Long = 60_000L

    /** 冷却窗内同一来源 IP 允许的 offer 次数。 */
    const val OFFER_MAX_PER_WINDOW: Int = 3

    /** 未确认（待 PIN / 已提交清单）的接收会话数上限。 */
    const val MAX_PENDING_SESSIONS: Int = 5
}

/** 并发连接闸：判定「当前活跃数还容不容得下一条连接」。 */
object ConnectionBudget {

    /** 是否允许再接入一条连接。[activeConnections] 为当前已建立的连接数。 */
    fun admit(
        activeConnections: Int,
        limit: Int = InboundLimits.MAX_CONCURRENT_CONNECTIONS,
    ): Boolean = activeConnections < limit
}

/** 未确认会话闸。 */
object PendingSessionBudget {

    /**
     * 是否拒绝这条新 offer。
     *
     * 只数「会弹出 PIN 或等着上传」的会话：已批准（TRANSFERRING）与终态都不占额度，
     * 否则一次正常大文件传输会把后续 offer 全堵死。
     */
    fun shouldReject(
        pendingSessions: Int,
        limit: Int = InboundLimits.MAX_PENDING_SESSIONS,
    ): Boolean = pendingSessions >= limit
}

/**
 * 每来源 IP 的 offer 冷却窗（滑动窗口，固定容量）。
 *
 * 判定与记账在同一次调用里完成："能不能收" 与 "记一次" 必须原子，
 * 分成两个方法调用方迟早只调其中一个（那样限流就成了摆设）。
 * 线程模型：引擎的 offer 跑在 IO 线程且可能并发，判定方法一律 @Synchronized。
 */
class OfferRateLimiter(
    private val maxPerWindow: Int = InboundLimits.OFFER_MAX_PER_WINDOW,
    private val windowMs: Long = InboundLimits.OFFER_WINDOW_MS,
) {

    private val hitsBySource = HashMap<String, ArrayDeque<Long>>()

    /**
     * 记账并判定：本次是否放行。
     *
     * @param source 来源标识（HTTP 层传来源 IP；取不到地址时传固定占位，等价于「同一个来源」）
     * @param nowMs 当前时刻
     * @return true = 放行（已计入窗口）；false = 超限（不计入，避免持续请求把窗口无限拖长）
     */
    @Synchronized
    fun allow(source: String, nowMs: Long): Boolean {
        prune(nowMs)
        val hits = hitsBySource.getOrPut(source) { ArrayDeque() }
        if (hits.size >= maxPerWindow) return false
        hits.addLast(nowMs)
        return true
    }

    /** 窗口内某个来源已计入的次数（测试 / 日志用）。 */
    @Synchronized
    fun count(source: String, nowMs: Long): Int {
        prune(nowMs)
        return hitsBySource[source]?.size ?: 0
    }

    /** 丢掉窗口外的记录与空桶，避免每个来源一条的常驻增长。 */
    private fun prune(nowMs: Long) {
        val iterator = hitsBySource.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val hits = entry.value
            while (hits.isNotEmpty() && nowMs - hits.first() >= windowMs) hits.removeFirst()
            if (hits.isEmpty()) iterator.remove()
        }
    }
}
