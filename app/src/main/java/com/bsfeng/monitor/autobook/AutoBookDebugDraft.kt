package com.bsfeng.monitor.autobook

/**
 * 调试用草稿工厂：模拟器 / 无支付 App 的环境里验证自动记账浮窗（纯逻辑，可 JVM 单测）。
 *
 * 字段取原型 s15 的示例（商户「好慷在家」/ ¥128.00 / 支出 / 居住·家政 / 支付宝），
 * 与真实识别结果同一数据形状，浮窗、分类识别高亮、落库链路都走同一份代码。
 *
 * 只在 `BuildConfig.DEBUG` 构建的设置页调试区可见（见 `SettingsScreen`）；
 * `sourceId` 带时间戳，避免与真实记录或上一次调试撞去重键。
 */
object AutoBookDebugDraft {

    /** 原型示例商户 */
    const val MERCHANT = "好慷在家"

    /** 原型示例金额 */
    const val AMOUNT = 128.00

    /** 原型示例二级分类（父级「居住」），用于验证识别项自动展开与圆点 */
    const val CATEGORY = "家政"

    /** 入账账户：与真实识别同一口径（支付宝渠道落支付宝账户） */
    const val ACCOUNT = "支付宝"

    /**
     * 构造调试草稿。
     *
     * @param nowMs 当前时间（注入便于单测）；提前 30 秒作为页面时间，浮窗「时间」行有值
     * @param channel 渠道标识，默认支付宝
     */
    fun create(nowMs: Long = System.currentTimeMillis(), channel: String = AutoBookRules.CHANNEL_ALIPAY): AutoBookDraft {
        val pageTime = java.time.LocalDateTime.now()
            .minusSeconds(30)
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return AutoBookDraft(
            channel = channel,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = AMOUNT,
            merchant = MERCHANT,
            categoryName = CATEGORY,
            accountName = ACCOUNT,
            payMethod = "",
            productDesc = "",
            orderId = "",
            platformCategory = "",
            timeText = pageTime,
            sourceId = "debug-" + nowMs,
            dedupKeys = emptyList(),
            markers = emptyList(),
            userRemark = "",
        )
    }
}
