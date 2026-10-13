package com.bsfeng.monitor.skip

/**
 * 页面快照导出请求（UI → 无障碍服务）。
 *
 * 用户在 App 内点击「导出快照」时，当前前台页面是本 App，导出的意义不大；
 * 因此这里只记录一个"到期时间"，由 [com.bsfeng.monitor.service.MonitorAccessibilityService]
 * 的轮询在到期时对**当时的前台页面**导出，用户可先切到目标 App。
 */
object SnapshotRequest {

    /** 默认延迟（毫秒）：给用户切到目标 App 的时间 */
    const val DEFAULT_DELAY_MS = 5_000L

    @Volatile
    private var dueAtMs = 0L

    /** 最近一次导出结果（绝对路径；null 表示失败） */
    @Volatile
    var lastPath: String? = null
        private set

    /** 发起一次导出请求 */
    fun request(delayMs: Long = DEFAULT_DELAY_MS) {
        val delay = if (delayMs <= 0) DEFAULT_DELAY_MS else delayMs
        dueAtMs = System.currentTimeMillis() + delay
    }

    /** 是否已有待处理的导出请求 */
    fun isPending(): Boolean = dueAtMs > 0L

    /** 到期则消费请求（只消费一次） */
    fun consumeIfDue(now: Long): Boolean {
        if (dueAtMs <= 0L || now < dueAtMs) return false
        dueAtMs = 0L
        return true
    }

    /** 取消待处理请求 */
    fun cancel() {
        dueAtMs = 0L
    }

    /** 记录导出结果 */
    fun record(path: String?) {
        lastPath = path
    }
}
