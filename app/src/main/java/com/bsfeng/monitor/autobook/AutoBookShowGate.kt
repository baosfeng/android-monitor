package com.bsfeng.monitor.autobook

/**
 * 「支付结果页稳定 1s 后再弹窗」的门控（纯逻辑，可 JVM 单测）。
 *
 * 真机反馈：支付成功页刚出现时确认浮窗就弹了出来，挡住支付动画、也让人误以为「还没付完就弹」。
 * 现在识别到账单后先排队 1s，到点再复核页面；本类只负责两件容易写错的事：
 *
 * 1. **同一笔已在队列时，后续事件不得重置计时** —— 支付结果页会连续抛多个 CONTENT_CHANGED，
 *    若每个事件都重新 postDelayed，浮窗会被无限推迟、永远不弹；
 * 2. **到点消费时只认同一笔** —— 1s 内用户返回 / 切走 / 又付了一笔时，不能把上一笔弹出来。
 *
 * 计时本身由调用方（AutoBookEngine）用 Handler 承担，本类不持有 Android 依赖，方便单测。
 */
internal class AutoBookShowGate {

    /** 已排队等待展示的账单身份键；null 表示当前没有排队任务。 */
    private var pendingSourceId: String? = null

    /**
     * 收到一笔识别结果。
     *
     * @return true = 需要新建延迟任务；false = **同一笔**已在队列里，保持原计时（不重置）。
     */
    fun schedule(sourceId: String): Boolean {
        if (pendingSourceId == sourceId) return false
        pendingSourceId = sourceId
        return true
    }

    /**
     * 延迟到点后的复核入口。
     *
     * @return true = 仍是排队的那一笔，允许展示；false = 已被取消 / 不是同一笔。
     * 无论返回什么，本次排队都会结束（不会重复消费）。
     */
    fun consume(sourceId: String): Boolean {
        val pending = pendingSourceId ?: return false
        pendingSourceId = null
        return pending == sourceId
    }

    /** 页面变化 / 服务销毁：取消排队。 */
    fun cancel() {
        pendingSourceId = null
    }

    /** 是否有排队的延迟任务（诊断用）。 */
    val isPending: Boolean get() = pendingSourceId != null

    /** 当前排队的账单身份键（诊断用）。 */
    val pending: String? get() = pendingSourceId
}
