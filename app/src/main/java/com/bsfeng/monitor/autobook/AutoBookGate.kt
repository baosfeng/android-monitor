package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookParser.kt 拆出：账单页门禁判定（什么页面算账单页 / 详情页 / 结果页）。
// 词表数据在 AutoBookGateTables；这里只留判定逻辑。

/** 账单页门禁（AutoBookParser 的公开 API 转发到这里）。 */
internal object AutoBookGate {

    fun hasMultipleTransactions(parser: AutoBookParser, lines: List<String>): Boolean {
        // ⚠️ 不再数「支付成功」出现次数：支付宝结果页标题与正文各有一处
        // （`支付成功￥0.01 回首页` + `支付成功`），旧实现把这个单笔页误杀成「非账单页」
        // （真机 2026-10-17：支付宝付完不弹窗）。
        val labelHits = lines.count { line -> AutoBookPatterns.ACCOUNT_LABELS.any { line.contains(it) } }
        if (labelHits >= AutoBookPatterns.MULTI_TRANSACTION_THRESHOLD) return true
        // 单笔页一屏只有一个**不同的**金额；出现两个不同金额才是流水 / 列表。
        // 结果页的标题 / 正文 / 商户行会重复同一个金额，按「行数」数会误伤。
        return countDistinctAmounts(parser, lines) >= AutoBookPatterns.MULTI_TRANSACTION_THRESHOLD
    }

    /**
     * 一屏里**不同金额值**的个数。
     *
     * 支付宝结果页 `支付成功￥0.01 回首页 | 0.01 | 楠楠… ￥0.01` 三行都是同一笔 0.01；
     * 支付消息列表则是 3.00 / 81.00 / 849.53 三个不同值。
     */
    private fun countDistinctAmounts(parser: AutoBookParser, lines: List<String>): Int =
        lines.asSequence()
            .mapNotNull { line -> parser.extractAmount(listOf(line))?.second }
            .toSet()
            .size

    /**
     * 当前页面是不是**单笔**账单 / 交易页。
     *
     * **白名单门禁**：默认拒绝，只有命中下面三种页面签名之一才放行 ——
     * 账单页的排版是有限几种，而「不像账单页」的页面是无穷的，黑名单永远列不完
     * （真机教训：2016-10-15 支付宝首页、2026-10-17 微信首页会话列表，都是补一条排除词又被下一个页面绕过）。
     *
     * 1. **独占结果页**：整行状态锚点（`支付成功`）+ 收尾按钮（`完成` / `查看账单`）；
     * 2. **账单详情页**：页面标题命中白名单（`账单详情` / `交易详情` / `付款详情` / `转账详情` / `交易成功` …）
     *    且 **≥1 个**强字段标签取到了值（支付时间 / 订单号 / 商户全称 / 商品说明 / 账单分类 / 交易状态）+ 有金额；
     * 3. **微信红包结果页**：`红包` + `微信支付` + 金额（红包没有锚点页，见 [isRedPacketResultPage]）。
     *
     * 公开给 [AutoBookEngine] 做诊断用：识别失败时要能区分「门禁拒绝」和「是账单页但字段没认出来」。
     */
    fun isBillPage(parser: AutoBookParser, lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        // 付款前页面（收银台 / 指纹 / 密码）：不是结果页，页面上的金额还没付出去
        if (lines.any { line -> AutoBookGateTables.PRE_PAY_WORDS.any { line.contains(it) } }) return false
        // 一屏多笔 = 列表 / 流水：单笔账单页只描述一笔交易
        if (hasMultipleTransactions(parser, lines)) return false
        // 白名单：命中任一账单页签名才识别
        if (isStandaloneResultPage(parser, lines)) return true
        if (isStrongBillDetailPage(parser, lines)) return true
        return isRedPacketResultPage(parser, lines)
    }

    /**
     * 白名单签名 2：**账单 / 交易详情页**。
     *
     * 页面名单先行：必须有详情页**标题**（页头文字，允许带图标 / 菜单前缀，
     * 如真机微信的 `服务 会员 交易详情`、支付宝的 `账单详情`、缴费页的 `交易成功`）。
     * 再叠加 1 个强字段 + 金额，确认页面里确实有交易数据。
     *
     * 为什么不再用「≥2 个强字段」单独放行：强字段是正文内容，聊天 / 通知 / 列表页也可能
     * 凑出两个（真机 2026-10-17 微信首页会话列表就被会话预览凑出过「收款方 → 值」）。
     * 标题是页面级元素，会话列表 / 聊天页都没有。
     */
    private fun isStrongBillDetailPage(parser: AutoBookParser, lines: List<String>): Boolean {
        if (parser.extractAmount(lines) == null) return false
        val hits = AutoBookGateTables.STRONG_DETAIL_LABELS.count { label -> parser.labelValue(lines, listOf(label)) != null }
        if (hits < 1) return false
        return hasDetailPageTitle(parser, lines)
    }

    /**
     * 页面标题是否属于详情页名单。
     *
     * 允许标题带前缀（真机微信导航栏与菜单项会被合成一行 `服务 会员 交易详情`），
     * 但长度只放宽 8 个字符 —— 聊天列表的长句（`收款方24小时内未接收你的转账，已过期`）不会命中。
     */
    private fun hasDetailPageTitle(parser: AutoBookParser, lines: List<String>): Boolean = lines.any { raw ->
        val text = raw.trim().trim { !it.isLetterOrDigit() && it != ' ' }.trim()
        DETAIL_PAGE_TITLES.any { title ->
            text == title || (text.length <= title.length + 8 && text.endsWith(title))
        }
    }

    /** 详情页标题 / 状态锚点（消息摘要里带着金额的行不算，见 [hasDetailPageTitle] 的长度约束） */
    private val DETAIL_PAGE_TITLES = listOf(
        "账单详情", "交易详情", "付款详情", "转账详情",
        "交易成功", "支付成功", "付款成功", "转账成功", "已支付"
    )

    /**
     * 当前页面是不是**交易详情页**（比结果页信息更全：支付时间 / 订单号 / 商品说明 / 商户全称）。
     *
     * 用于「已经记过账的那笔，用户后来打开详情页」：不再弹记账浮窗，而是把详情页字段补进已有记录。
     * 判定比 [isBillPage] 更严 —— 结果页只有 `交易方式` 这类弱字段，不能算详情页。
     */
    fun isDetailPage(parser: AutoBookParser, lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        if (lines.any { line -> AutoBookGateTables.PRE_PAY_WORDS.any { line.contains(it) } }) return false
        if (hasMultipleTransactions(parser, lines)) return false
        if (isListOrSummaryPage(parser, lines)) return false
        if (AutoBookPatterns.FUTURE_WORDS.any { word -> lines.any { it.contains(word) } }) return false
        return AutoBookGateTables.STRONG_DETAIL_LABELS.any { label -> parser.labelValue(lines, listOf(label)) != null }
    }

    private fun isListOrSummaryPage(parser: AutoBookParser, lines: List<String>): Boolean =
        lines.any { line ->
            AutoBookGateTables.LIST_PAGE_WORDS.any { line.contains(it) } ||
                AutoBookGateTables.RELATIVE_TIME.containsMatchIn(line) ||
                AutoBookGateTables.CHAT_TIME_DIVIDER.matches(line.trim())
        }

    private fun isStandaloneResultPage(parser: AutoBookParser, lines: List<String>): Boolean {
        val hasAnchor = lines.any { AutoBookGateTables.STATUS_ANCHOR.matches(it.trim()) }
        if (!hasAnchor) return false
        return lines.any { line -> AutoBookGateTables.RESULT_PAGE_ACTIONS.any { line.contains(it) } }
    }

    /**
     * 是否**微信红包发送结果页**。
     *
     * 真机实测（2026-10-16，微信 8.0.79）：红包付款成功后**没有**「支付成功」锚点页 ——
     * 转账有（`支付成功` + `完成`），红包发完直接回聊天页；而**聊天页的红包气泡不含金额**
     * （只写「恭喜发财，大吉…」+「微信红包」）。金额只在这个极简结果页里出现过：
     *
     * `   [返回 | 微信红包 | ￥0.01 | 微信支付]`
     *
     * 因此不认这一页，红包就永远记不上（真机复现：用户发红包后浮窗不弹）。
     *
     * ⚠️ 必须叠加 [AutoBookGateTables.RED_PACKET_PRE_PAY_WORDS] 排除，否则会在**付款之前**弹窗。
     */
    private fun isRedPacketResultPage(parser: AutoBookParser, lines: List<String>): Boolean {
        // 红包结果页是**极简页**（真机只有 4 行：`返回 | 微信红包 | ￥0.01 | 微信支付`）。
        // 会话列表 / 聊天页动辄 10+ 行，靠行数先挡一道。
        if (lines.size > RED_PACKET_RESULT_MAX_LINES) return false
        val joined = lines.joinToString(" ")
        // 必须是产品名「微信红包」整词 —— 会话预览「红包退款到账通知」里的「红包」不算
        if (!joined.contains("微信红包")) return false
        if (!joined.contains("微信支付")) return false
        if (AutoBookGateTables.RED_PACKET_PRE_PAY_WORDS.any { joined.contains(it) }) return false
        // 红包结果页的金额带货币符号（`￥0.01`）；聊天 / 群消息里的「40元」不是红包金额
        return lines.any { AutoBookPatterns.SYMBOL_AMOUNT.containsMatchIn(parser.stripClock(it)) }
    }

    /** 红包结果页最多这么多行（真机结果页 4 行；会话列表 / 聊天页远多于它） */
    private const val RED_PACKET_RESULT_MAX_LINES = 8
}
