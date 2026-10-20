package com.bsfeng.monitor.finance

/**
 * 旧版自动记账的「说明」拼接残留识别（纯逻辑，可单测）。
 *
 * 2026-10 之前，`AutoBookDraft.remark()` 会把商户 + 商品说明 + 付款方式拼成
 * 「商户·商品说明·付款方式」写进 remark；2026-10-20 起说明只存用户手写内容。
 * 这里按**旧算法精确重算**并比对，只清理能确定是自动拼出来的记录：
 *
 * - 只处理 `source` 以 `auto_` 开头的自动记账记录（手动记账 / 熊猫导入不碰）；
 * - 旧记录若还拼了用户预采集备注，值会多一段、重算不相等 → 保留（那是用户写的内容）；
 * - 拼接结果只有一段（只有商户名）时无法与用户手写区分 → 保留，避免误清。
 */
object LegacyRemarkCleaner {

    private const val MAX_LENGTH = 80

    /** 旧版 `remark()` 的拼接结果（不含 userRemark：旧记录没有独立列保存它） */
    fun aggregated(
        merchant: String,
        productDesc: String,
        payMethod: String,
        channelLabel: String,
    ): String {
        val parts = mutableListOf<String>()
        val main = merchant.ifBlank { productDesc }
        if (main.isNotBlank()) parts += main
        if (productDesc.isNotBlank() && productDesc != main) parts += productDesc
        if (payMethod.isNotBlank()) parts += payMethod
        if (parts.isEmpty()) parts += channelLabel
        return parts.joinToString("·").take(MAX_LENGTH)
    }

    /**
     * 这条 remark 是不是旧版自动拼接出来的「单据汇总」。
     *
     * 只认「至少两段」的拼接（含 `·`）：单段无法与用户手写区分，宁可保留。
     */
    fun isLegacyAggregate(
        remark: String,
        merchant: String,
        productDesc: String,
        payMethod: String,
        channelLabel: String,
    ): Boolean {
        if (remark.isBlank()) return false
        val aggregated = aggregated(merchant, productDesc, payMethod, channelLabel)
        return aggregated.contains("·") && remark == aggregated
    }

    /** `auto_alipay` / `auto_wechat` → 旧算法里的渠道名 */
    fun channelLabelOf(source: String): String = when {
        source.endsWith("alipay") -> "支付宝"
        source.endsWith("wechat") -> "微信"
        else -> ""
    }
}
