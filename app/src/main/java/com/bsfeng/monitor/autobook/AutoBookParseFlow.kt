package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookParser.kt 拆出：主解析流水线（门禁 → 金额 → 字段 → 幂等键 → 草稿）。
// 返回 null 表示「这条不是可记账的账单」；调用方不再二次判断。

/** 主解析流水线（AutoBookParser.parse / parseNotification 的实现）。 */
internal object AutoBookParseFlow {

    fun parseInternal(parser: AutoBookParser, 
        rule: AutoBookRule,
        lines: List<String>,
        nowMs: Long,
        requireBillPage: Boolean
    ): AutoBookDraft? {
        if (lines.isEmpty()) return null

        val markers = rule.pageMarkers.filter { marker -> lines.any { it.contains(marker) } }
        if (markers.isEmpty()) return null
        if (rule.excludeMarkers.any { marker -> lines.any { it.contains(marker) } }) return null

        // 页面门禁：只在账单 / 交易页识别，挡掉首页消息摘要、账单流水列表这类误触发
        if (requireBillPage && !parser.isBillPage(lines)) return null

        val joined = lines.joinToString(" ")
        // 未来时态（活动预告、待扣款提醒）不是已发生的交易，整条放弃
        if (AutoBookPatterns.FUTURE_WORDS.any { joined.contains(it) }) return null

        val amount = parser.extractAmount(lines) ?: return null
        if (amount.second <= 0.0) return null
        // 提现/充值属于账户间转移，记了会让总身家凭空变化，不记
        if (AutoBookPatterns.TRANSFER_SKIP_WORDS.any { amount.first.contains(it) }) return null

        val type = parser.detectType(lines, amount.first)
        val merchant = parser.extractMerchant(lines)
        val payMethod = parser.extractAccount(lines).orEmpty()
        val productDesc = parser.extractProductDesc(lines)
        val timeText = parser.extractTime(lines) ?: ""
        val platformCategory = parser.labelValue(lines, AutoBookPatterns.CATEGORY_LABELS)
        val category = parser.mapCategory(platformCategory, merchant, type)
        val orderId = parser.extractOrderId(lines)
        val sourceId = parser.buildSourceId(rule.channel, type, amount.second, merchant, orderId, timeText, nowMs)
        val dedupKeys = parser.buildDedupKeys(rule.channel, type, amount.second, orderId, timeText)

        return AutoBookDraft(
            channel = rule.channel,
            type = type,
            amount = amount.second,
            merchant = merchant,
            categoryName = category,
            accountName = rule.accountName,
            payMethod = payMethod,
            productDesc = productDesc,
            orderId = orderId,
            platformCategory = platformCategory.orEmpty(),
            timeText = timeText,
            sourceId = sourceId,
            dedupKeys = dedupKeys,
            markers = markers
        )
    }

    /**
     * 支付宝红包发出后的**聊天页**识别（成功页不存在的兜底通道）。
     *
     * 背景（真机 2026-10-17 16:28 / 16:37）：支付宝发红包付款成功后没有任何结果页，直接回到聊天页；
     * 红包气泡给 `红包已被领取` / `张三领取了你的 红包`，有时也带 `支付宝红包` + `¥0.01 + 留言`。
     * 金额、付款方式、备注仍以付款前页面预采集的 [pre] 为准（气泡金额只用来核对）。
     *
     * 防误判：
     * - 只认支付宝（微信红包有独立结果页，走 [isRedPacketResultPage]）；
     * - 必须是**聊天页**（发红包输入页 / 收银台 / 指纹页都不含「发送消息」这类会话特征）；
     * - 光有「红包」两个字不算：要么有「已领取」强信号，要么 [allowBubbleOnly]（刚走过红包付款页）
     *   且页面出现 `支付宝红包` 气泡、金额与预采集一致；
     * - 预采集必须在 [AutoBookDraftMerger.PRE_DRAFT_TTL_MS] 内。
     */
}
