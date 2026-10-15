package com.bsfeng.monitor.autobook

/**
 * 自动记账规则。
 *
 * 一条规则描述「在哪个 App 的哪类页面上可以识别出一笔账单」。规则只做**页面判定**
 * （特征词命中 / 排除词命中），字段提取由 [AutoBookParser] 统一完成 —— 新增 App
 * 只需要加一条规则数据，不用改解析逻辑。
 *
 * @param packageName 目标 App 包名
 * @param channel 渠道标识（[CHANNEL_ALIPAY] / [CHANNEL_WECHAT]），落库时拼成 source
 * @param accountName 默认入账账户名（对应 finance_accounts.name），页面上的付款方式优先
 * @param pageMarkers 页面特征词：命中任意一个才认为是交易结果页
 * @param excludeMarkers 排除词：命中任意一个即放弃（列表页 / 统计页 / 设置页等噪声页面）
 */
data class AutoBookRule(
    val packageName: String,
    val channel: String,
    val accountName: String,
    val pageMarkers: List<String>,
    val excludeMarkers: List<String> = emptyList()
)

/**
 * 内置规则库（覆盖微信 / 支付宝）。
 *
 * 设计取舍：特征词全部取**中文界面文案**而不是 viewId —— 两家 App 的支付结果页在版本升级时
 * viewId 会变（真机实测支付宝详情页的节点几乎全无 resource-id），而「交易成功」「账单详情」
 * 这类文案相对稳定。
 */
object AutoBookRules {

    const val CHANNEL_ALIPAY = "alipay"
    const val CHANNEL_WECHAT = "wechat"

    /** 支付宝包名 */
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 微信包名 */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 支付结果页通用特征词 */
    private val PAY_RESULT_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "收款成功",
        "还款成功", "退款成功", "交易详情", "账单详情", "付款详情", "转账详情", "已支付",
        // 通知句式：支付宝/微信的推送常常只写「你有一笔X元的支出」，没有「成功」二字
        "你有一笔", "交易提醒"
    )

    /**
     * 排除词：**只放真正的列表/统计页特征**。
     *
     * ⚠️ 不要把「全部账单」「账单管理」放进来 —— 真机实测这两个词就出现在账单**详情页**上
     * （右上角按钮「全部账单」、底部入口「账单管理」），放进去会让详情页整页被排除。
     */
    private val COMMON_EXCLUDES = listOf(
        "账单统计", "收支分析", "月账单", "年度账单", "帮助中心"
    )

    val alipay = AutoBookRule(
        packageName = ALIPAY_PACKAGE,
        channel = CHANNEL_ALIPAY,
        accountName = "支付宝",
        pageMarkers = PAY_RESULT_MARKERS + listOf("付款给", "收款到账"),
        excludeMarkers = COMMON_EXCLUDES
    )

    val wechat = AutoBookRule(
        packageName = WECHAT_PACKAGE,
        channel = CHANNEL_WECHAT,
        accountName = "微信钱包",
        pageMarkers = PAY_RESULT_MARKERS + listOf("微信支付", "已收款", "零钱", "转账给"),
        excludeMarkers = COMMON_EXCLUDES + listOf("朋友圈", "视频号")
    )

    /** 全部内置规则 */
    val all: List<AutoBookRule> = listOf(alipay, wechat)

    /** 按包名取规则，未覆盖的 App 返回 null */
    fun forPackage(packageName: String?): AutoBookRule? =
        all.firstOrNull { it.packageName == packageName }
}
