package com.bsfeng.monitor.skip

/**
 * 页面快照导出请求（UI → 无障碍服务）。

 * 用户在 App 内点击「导出快照」时，当前前台页面是本 App，导出它没有意义；
 * 因此这里只记录一个"待导出"意图，由 [com.bsfeng.monitor.service.MonitorAccessibilityService]
 * 的轮询在合适时机对**当时的前台页面**导出。
 *
 * ## 两种触发方式
 * - [requestOnAppSwitch]（推荐）：等用户**切到别的 App 之后**再等 [SWITCH_SETTLE_MS] 导出。
 *   真机取证（2026-10-13，30 份快照）：固定延时模式经常导到 App 首页（开屏页已消失），
 *   而开屏广告页在"切过去 2~3 秒"时通常正显示跳过按钮，因此切换触发 + 2.5 秒的命中率高得多。
 * - [request]：固定延时（从现在起算），保留给测试与特殊场景。
 */
object SnapshotRequest {

    /** 固定延时模式的默认延迟（毫秒） */
    const val DEFAULT_DELAY_MS = 5_000L

    /** 「切到目标 App 后」的默认等待（毫秒）：此时开屏广告已渲染出跳过按钮，且通常还没消失 */
    const val SWITCH_SETTLE_MS = 2_500L

    @Volatile
    private var dueAtMs = 0L

    /** 等待切换的请求：非 null = 「等离开该包名后再开始倒计时」 */
    private var switchOriginPackage: String? = null
    private var switchDelayMs = SWITCH_SETTLE_MS

    /** 最近一次导出结果（绝对路径；null 表示失败） */
    @Volatile
    var lastPath: String? = null
        private set

    /** 固定延时导出（从现在起算） */
    fun request(delayMs: Long = DEFAULT_DELAY_MS) {
        val delay = if (delayMs <= 0) DEFAULT_DELAY_MS else delayMs
        switchOriginPackage = null
        dueAtMs = System.currentTimeMillis() + delay
    }

    /**
     * 推荐用法：切到目标 App（开屏页）后 [delayMs] 再导出。
     *
     * @param originPackage 发起请求时所在的前台包名（通常是本 App 的包名）
     * @param delayMs 切走之后的等待时间（毫秒，<=0 取 [SWITCH_SETTLE_MS]）
     */
    fun requestOnAppSwitch(originPackage: String, delayMs: Long = SWITCH_SETTLE_MS) {
        val delay = if (delayMs <= 0) SWITCH_SETTLE_MS else delayMs
        switchOriginPackage = originPackage
        switchDelayMs = delay
        dueAtMs = 0L
    }

    /**
     * 前台 App 变化（服务轮询每秒调用一次）：切走了才开始倒计时。
     *
     * 没切走（仍停留在发起请求的 App）时什么都不做——避免导到本 App 自己的页面。
     */
    fun onForegroundPackage(packageName: String, now: Long) {
        val origin = switchOriginPackage ?: return
        if (packageName == origin) return
        switchOriginPackage = null
        dueAtMs = now + switchDelayMs
    }

    /** 是否已有待处理的导出请求（含"等切换"状态） */
    fun isPending(): Boolean = dueAtMs > 0L || switchOriginPackage != null

    /** 到期则消费请求（只消费一次） */
    fun consumeIfDue(now: Long): Boolean {
        if (dueAtMs <= 0L || now < dueAtMs) return false
        dueAtMs = 0L
        return true
    }

    /** 取消待处理请求 */
    fun cancel() {
        dueAtMs = 0L
        switchOriginPackage = null
    }

    /** 记录导出结果 */
    fun record(path: String?) {
        lastPath = path
    }
}
