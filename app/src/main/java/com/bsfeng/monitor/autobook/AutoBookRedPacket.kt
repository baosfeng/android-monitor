package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookParser.kt 拆出：微信/支付宝红包的聊天页兜底识别。
// 红包付款成功后可能没有结果页，而是回到聊天页（气泡不带金额）；
// 这里靠付款前预采集的金额 + 指纹标记，把「已发出的红包」补记出来。
import kotlin.math.abs

/** 红包聊天页识别（AutoBookParser 的扩展，公开 API 与原成员一致）。 */
internal object AutoBookRedPacket {

    fun parseSentRedPacketChat(
        parser: AutoBookParser,
        rule: AutoBookRule,
        lines: List<String>,
        pre: PrePaymentDraft?,
        nowMs: Long,
        redPacketFlowPending: Boolean = false,
        allowBubbleOnly: Boolean = false
    ): AutoBookDraft? {
        if (!redPacketFlowPending) return null
        if (rule.channel != AutoBookRules.CHANNEL_ALIPAY) return null
        if (pre != null && nowMs - pre.capturedAtMs > AutoBookDraftMerger.PRE_DRAFT_TTL_MS) return null
        if (!isSentRedPacketChatPage(parser, lines, allowBubbleOnly)) return null

        // 只有气泡、没有「已领取」强信号时，再用气泡金额核对一次：
        // 聊天记录里旧红包金额对不上，就不能当成本笔（宁可漏记也不能记错）。
        val joined = lines.joinToString(" ")
        val hasClaimSignal = AutoBookGateTables.RED_PACKET_SENT_WORDS.any { joined.contains(it) }
        val bubbleAmount = extractRedPacketBubbleAmount(parser, lines)
        val amount = when {
            pre != null && pre.amount > 0.0 -> {
                if (bubbleAmount != null && !hasClaimSignal && abs(bubbleAmount - pre.amount) > 0.01) {
                    return null
                }
                pre.amount
            }
            // 预采集可能因为服务重启 / 事件丢失为空；气泡自带金额时仍要能记（用户要求「红包出现就记」）。
            // 这条路径只允许在「刚走过红包付款页」时启用，避免历史聊天里的旧红包误弹。
            allowBubbleOnly && bubbleAmount != null && bubbleAmount > 0.0 -> bubbleAmount
            else -> return null
        }
        val merchant = pre?.merchant?.ifBlank { null }
            ?: extractRedPacketClaimer(parser, lines).ifBlank { extractChatContact(parser, lines) }
        val type = AutoBookDraft.TYPE_EXPENSE
        return AutoBookDraft(
            channel = rule.channel,
            type = type,
            amount = amount,
            merchant = merchant,
            // 发红包是固定语义：商户名是领取人（张三），平台分类也拿不到，走关键词只会落到「其他」
            categoryName = AutoBookPatterns.RED_PACKET_CATEGORY,
            accountName = rule.accountName,
            payMethod = pre?.payMethod.orEmpty(),
            productDesc = "",
            orderId = "",
            platformCategory = "",
            timeText = "",
            sourceId = parser.buildSourceId(rule.channel, type, amount, merchant, "", "", nowMs),
            dedupKeys = parser.buildDedupKeys(rule.channel, type, amount, "", ""),
            markers = listOf("红包已发出")
        )
    }

    /**
     * 红包付款前页面（输入页 / 塞钱页 / 确认页）—— 用于给 [parseSentRedPacketChat] 打「这笔是红包」标记。
     *
     * 为什么要单独认：支付宝红包付款成功后没有结果页，聊天页里本来就有历史红包气泡；
     * 只有「刚刚走过红包付款流程」这一事实，才能把新气泡和旧气泡区分开。
     */
    fun isRedPacketPrePayPage(parser: AutoBookParser, lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        val joined = lines.joinToString(" ")
        if (!joined.contains("红包")) return false
        // 聊天页底部也有「发红包」快捷入口，必须排除会话页
        if (AutoBookGateTables.CHAT_PAGE_WORDS.any { joined.contains(it) }) return false
        if (AutoBookGateTables.RED_PACKET_ENTRY_WORDS.none { joined.contains(it) }) return false
        // 收银台把金额写在「付款金额0.01元人民币」里，而这一行同时含「红包」——
        // extractAmount 会按金额噪声跳过它，所以这里用红包专用正则再兜一次。
        return parser.extractAmount(lines) != null || AutoBookGateTables.RED_PACKET_AMOUNT_TEXT.containsMatchIn(joined)
    }

    /**
     * 聊天页上是否已有「我发出的红包」信号。
     *
     * 两种信号：
     * - 强信号：`红包已被领取` / `领取了你的红包`（对方已领取，金额直接取预采集）；
     * - 弱信号：聊天页里出现 `支付宝红包` 气泡 —— 对方**还没领取**时只有这一种。
     *   弱信号只在 [allowBubbleOnly] 为 true 时启用，而它由「刚刚走过红包付款前页面」
     *   （[isRedPacketPrePayPage]）决定，避免用户在历史聊天记录里看到旧红包就误弹。
     */
    private fun isSentRedPacketChatPage(parser: AutoBookParser, lines: List<String>, allowBubbleOnly: Boolean): Boolean {
        val joined = lines.joinToString(" ")
        if (!joined.contains("红包")) return false
        if (!AutoBookGateTables.CHAT_PAGE_WORDS.any { joined.contains(it) }) return false
        if (AutoBookGateTables.PRE_PAY_WORDS.any { joined.contains(it) }) return false
        if (AutoBookGateTables.RED_PACKET_SENT_WORDS.any { joined.contains(it) }) return true
        // 对方还没领取：只有 `支付宝红包` 气泡。必须已经「验过指纹/密码」才算付款成功，
        // 避免在发红包页取消后打开旧聊天被历史红包触发。
        return allowBubbleOnly && joined.contains("支付宝红包")
    }

    /** 红包付款流程里是否已经到了指纹 / 密码验证页（离付款成功只差一步）。 */
    fun isRedPacketFingerprintPage(parser: AutoBookParser, lines: List<String>): Boolean =
        lines.any { line ->
            AutoBookGateTables.RED_PACKET_FINGERPRINT_WORDS.any { line.contains(it) }
        }

    /**
     * 从红包气泡邻域取金额：`支付宝红包 / 清风明月 / ¥0.01 / 示例留言` → 0.01。
     *
     * 不能对整页 [extractAmount]：聊天记录里可能还有别的转账/红包气泡金额，页面上先出现的未必是这一笔。
     * 只取「支付宝红包」标签后的 5 行，命中带货币符号 / 两位小数的金额。
     */
    private fun extractRedPacketBubbleAmount(parser: AutoBookParser, lines: List<String>): Double? {
        val index = lines.indexOfFirst { it.contains("支付宝红包") }
        if (index < 0) return null
        val end = minOf(lines.size, index + 5)
        for (i in index until end) {
            val line = lines[i]
            if (parser.run { isAmountNoise(line) }) continue
            val match = AutoBookGateTables.RED_PACKET_BUBBLE_AMOUNT.find(line) ?: continue
            parser.run { toAmount(match.groupValues[1]) }?.let { return it }
        }
        return null
    }

    /** 聊天页顶部联系人名（`返回 资金往来记录 聊天设置` 的下一行，如 `张三`） */
    private fun extractChatContact(parser: AutoBookParser, lines: List<String>): String {
        val index = lines.indexOfFirst { it.contains("资金往来记录") || it.contains("聊天设置") }
        if (index < 0) return ""
        val end = minOf(lines.size, index + 4)
        for (i in index + 1 until end) {
            val value = lines[i].trim()
            if (value.isEmpty() || value.length > 20) continue
            if (value.contains("红包") || value.contains("表情") || value.contains("消息")) continue
            if (value.matches(Regex("^[0-9:：\\s]+$"))) continue
            return value
        }
        return ""
    }

    /** 从领取提示里取领取人：`icon 张三领取了你的 红包` → `张三` */
    private fun extractRedPacketClaimer(parser: AutoBookParser, lines: List<String>): String {
        for (line in lines) {
            val m = AutoBookGateTables.RED_PACKET_CLAIMED_BY.find(line) ?: continue
            val name = m.groupValues[1].trim().trimStart('，', ',', '、', ' ')
            if (name.isNotEmpty() && !name.contains("你") && !name.contains("红包")) return name.take(20)
        }
        return ""
    }
}
