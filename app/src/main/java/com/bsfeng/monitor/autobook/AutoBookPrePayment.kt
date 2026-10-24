package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookParser.kt 拆出：付款前页面预采集（金额 / 收款方 / 付款方式 / 备注）。
// 这些字段在支付结果页往往缺失，只能趁用户还在付款页时先抓到，成功页再补齐。

/** 付款前页面预采集（AutoBookParser 的公开 API 转发到这里）。 */
internal object AutoBookPrePayment {

    /**
     * 是否是付款前页面（收银台 / 指纹 / 密码输入 / 转账确认）。
     *
     * 注意：它**不是**账单页 —— [isBillPage] 会拒绝它；这里只用于预采集。
     */
    fun isPrePaymentPage(parser: AutoBookParser, lines: List<String>): Boolean =
        parser.extractAmount(lines) != null &&
            lines.any { line -> AutoBookGateTables.PRE_PAY_PAGE_WORDS.any { line.contains(it) } }

    /**
     * 预采集付款前页面：金额 / 收款方 / 付款方式 / 用户备注。
     *
     * 这些字段在支付结果页往往缺失（微信转账成功页尤其极简），只能趁用户还在付款页时抓到。
     * 返回 null 表示「不是付款前页面」或「金额都取不到」，调用方不应缓存。
     */
    fun parsePrePayment(parser: AutoBookParser, rule: AutoBookRule, lines: List<String>, nowMs: Long): PrePaymentDraft? {
        if (!isPrePaymentPage(parser, lines)) return null
        val amount = parser.extractAmount(lines) ?: return null
        return PrePaymentDraft(
            channel = rule.channel,
            type = parser.detectType(lines, amount.first),
            amount = amount.second,
            merchant = extractPrePaymentMerchant(parser, lines),
            payMethod = parser.extractAccount(lines).orEmpty(),
            remark = extractPrePaymentRemark(parser, lines),
            capturedAtMs = nowMs
        )
    }

    /**
     * 付款前页面的收款方。
     *
     * 微信：「付款给张三(***)」直接取后半段；「付款给个人」则取下一行（真机实测样例：
     * `付款给个人 头像` → `张三(***)`）。
     * 支付宝：走「收款方 / 商户名称」标签。
     */
    private fun extractPrePaymentMerchant(parser: AutoBookParser, lines: List<String>): String {
        for ((index, line) in lines.withIndex()) {
            val at = line.indexOf("付款给")
            if (at < 0) continue
            // 尾部可能粘着按钮文字（真机：`付款给张三(***) 返回`）—— 收款方不含空格，取第一个词
            val tail = parser.stripZeroWidth(line.substring(at + 3)).substringBefore(" ").trim()
            if (tail.isNotEmpty() && !tail.startsWith("个人")) return tail.take(40)
            val next = lines.getOrNull(index + 1)?.let { parser.stripZeroWidth(it) }
            if (!next.isNullOrEmpty() && !parser.isLabelLike(next)) return next.take(40)
        }
        val labeled = parser.labelValue(lines, listOf("收款方", "收款方全称", "收款人", "商户名称", "商户全称"))
        if (!labeled.isNullOrBlank()) return labeled.take(40)
        return ""
    }

    /**
     * 付款前页面上用户填的备注。
     *
     * 真机原文形如 `备注 修⁠改`（含零宽字符）或 `备注 午饭`；「修改 / 添加备注」是入口文案，
     * 不能当成备注内容。
     */
    private fun extractPrePaymentRemark(parser: AutoBookParser, lines: List<String>): String {
        for (line in lines) {
            val at = line.indexOf("备注")
            if (at < 0) continue
            val tail = parser.stripZeroWidth(line.substring(at + 2)).trimStart('：', ':').trim()
            // 只认同一行的值：真机的「备注」行后面会跟「付款」按钮 / 数字键盘 /「修改」入口，
            // 取下一行会得到 `付款`、`1 2 3 删除` 这种脏值（2026-10-17 真机日志）。
            if (tail.isEmpty() || tail in AutoBookFieldParse.REMARK_PLACEHOLDERS || looksLikeUiText(parser, tail)) continue
            return tail.take(40)
        }
        return ""
    }

    /** 备注行里混进的 UI 文案 / 键盘行（不是用户输入）。 */
    private fun looksLikeUiText(parser: AutoBookParser, text: String): Boolean =
        text.any { it.isDigit() } ||
            listOf(
                "付款", "取消", "确定", "删除", "支付", "返回",
                "修改", "添加备注", "备注一下", "收款人可见"
            ).any { text.contains(it) }
}
