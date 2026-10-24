package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookParser.kt 拆出：时间 / 订单号 / 标签取值与「像不像字段值」的判定。
// 前半是取值规则，后半是防误取（通知文案、时间戳、标签本身都不能当值）。

/** 时间 / 订单号 / 标签取值与防误取判定。 */
internal object AutoBookFieldParse {

    /**
     * 微信「添加备注」弹窗：`添加备注 | 随便 | 取消 确定`。
     *
     * 这个弹窗**没有金额**，不会走预采集；但用户填的备注只在这里可见 —— 单独抓出来，
     * 由 [AutoBookEngine] 并进当前预采集。
     *
     * @return 用户输入；不是备注弹窗（或只是空备注）返回 null
     */
    fun parseRemarkDialog(lines: List<String>): String? {
        // 只认「弹窗自己」的紧凑行集：支付宝的转账浮层会和首页文本混在一个窗口里，
        // 行数一多就分不清哪一行是用户备注（真机 2026-10-17：首页「多云 27℃」被当备注候选）
        if (lines.size > 8) return null
        val hasTitle = lines.any { it.contains("添加备注") || it.trim() == "备注" }
        if (!hasTitle) return null
        val hasCancel = lines.any { it.contains("取消") }
        // 微信按钮是「确定」，支付宝是「确认」——两个都要认
        val hasConfirm = lines.any { it.contains("确定") || it.contains("确认") }
        if (!hasCancel || !hasConfirm) return null
        for (line in lines) {
            val text = stripZeroWidth(line)
            if (text.isEmpty()) continue
            if (text.contains("添加备注") || text == "备注") continue
            if (text.contains("取消") || text.contains("确定") || text.contains("确认")) continue
            if (text in REMARK_PLACEHOLDERS) continue
            return text.take(40)
        }
        return null
    }

    /** 去掉零宽字符（真机「修改」两个字之间夹了一个 U+2060，直接比较永远不相等）。 */
    internal fun stripZeroWidth(text: String): String =
        text.replace(Regex("[\u200B-\u200D\u2060\uFEFF]"), "").trim()

    /** 备注行的占位文案（不是用户填的内容）。 */
    internal val REMARK_PLACEHOLDERS = setOf("修改", "添加备注", "添加", "编辑", "填写备注", "备注一下", "无")

    /** 交易时间：优先标签行的值，其次全页首个日期时间串 */
    fun extractTime(parser: AutoBookParser, lines: List<String>): String? {
        for (label in AutoBookPatterns.TIME_LABELS) {
            val value = labelValue(parser, lines, listOf(label))
            if (value != null) {
                AutoBookPatterns.DATE_TIME.find(value)?.let { return it.value }
            }
        }
        for (line in lines) {
            AutoBookPatterns.DATE_TIME.find(line)?.let { return it.value }
        }
        return null
    }

    /**
     * 订单号：先按独立标签取，再扫行内标签。
     *
     * ⚠️ 真机实测：支付宝账单详情页并不给订单号单独一行，而是把它塞在别的值里 ——
     * `商品说明 充值:阿里云服务购买,业务交易号:CFP000000000000000000`。
     * 订单号一漏，去重就退化成「金额 + 商户」的模糊匹配，而商户名在渲染中间态下会抖。
     */
    fun extractOrderId(parser: AutoBookParser, lines: List<String>): String {
        for (label in AutoBookPatterns.ORDER_LABELS) {
            val value = labelValue(parser, lines, listOf(label)) ?: continue
            AutoBookPatterns.ORDER_ID.find(value)?.let { return it.value }
        }
        for (line in lines) {
            val match = AutoBookPatterns.ORDER_LABEL_INLINE.find(line) ?: continue
            val tail = line.substring(match.range.last + 1)
            AutoBookPatterns.ORDER_ID.find(tail)?.let { return it.value }
        }
        return ""
    }

    /**
     * 取「标签 → 值」：先看同一行里标签后面的内容（`付款方式 余额宝` → 余额宝），
     * 同行没有值就取下一行（`付款方式` 换行 `余额宝`）。
     *
     * @return 取到的值；标签不存在或取不到值时返回 null
     */
    fun labelValue(parser: AutoBookParser, lines: List<String>, labels: List<String>): String? {
        for (label in labels) {
            for (index in lines.indices) {
                val line = lines[index]
                val at = line.indexOf(label)
                if (at < 0) continue

                // 前缀误命中防护：短标签会命中长标签并取出半截值
                // （「付款方」命中「付款方式」→「式 招商银行信用卡(8888)」；
                //   「收款方」命中「收款方全称」→「全称 阿里云计算有限公司」）。
                // 标签后面紧跟汉字即视为更长词的片段，跳过；紧跟冒号 / 空格 / 字母数字仍然放行。
                val after = at + label.length
                if (after < line.length && isCjkIdeograph(line[after])) continue
                // 标签前面紧跟汉字 = 它是更长词的后半段（真机 2026-10-17：
                // 「推荐付款方式」里的「付款方式」被当标签，下一行金额 0.01 被当成付款方式）
                if (at > 0 && isCjkIdeograph(line[at - 1])) continue

                // 同行右侧：去掉标签本身、冒号与可能多出来的逗号
                // （微信收银台原文是 `付款方式,已选择零钱,更改 更改`，逗号会粘在值前面）
                val inline = line.substring(at + label.length)
                    .trim()
                    .trimStart('：', ':', ' ', '　', ',', '，', '、', ';', '；')
                    .trim()
                if (inline.isNotEmpty() && !isLabelLike(inline) && !isNotificationText(inline) &&
                    !isClockOnly(inline)
                ) {
                    return inline
                }

                // 下一行
                val next = lines.getOrNull(index + 1)?.trim()
                    ?.trimStart(',', '，', '、', ';', '；')
                    ?.trim()
                if (!next.isNullOrEmpty() && !isLabelLike(next) && !isNotificationText(next) &&
                    !isClockOnly(next)
                ) {
                    return next
                }
            }
        }
        return null
    }

    /**
     * 取到的「值」其实是系统通知 / 会话预览。
     *
     * 真机事故（2026-10-17 微信首页会话列表）：`收款方24小时内未接收你的转账，已过期`
     * 会被 `收款方` 标签取成值、让整页看起来像账单详情页。通知文案不能当字段值。
     */
    private fun isNotificationText(text: String): Boolean = AutoBookGateTables.NOTIFICATION_TEXT.containsMatchIn(text)

    /** 值只是一个时间戳（`20:36`）：会话列表的列表项时间，不是字段值 */
    private fun isClockOnly(text: String): Boolean = AutoBookPatterns.CLOCK.matches(text.trim())

    /** 是否是汉字（判断标签后面是否紧跟了更长词的片段；全角标点不算） */
    private fun isCjkIdeograph(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    internal fun isLabelLike(text: String): Boolean {
        val labels = AutoBookPatterns.MERCHANT_LABELS + AutoBookPatterns.ACCOUNT_LABELS + AutoBookPatterns.TIME_LABELS + AutoBookPatterns.CATEGORY_LABELS + AutoBookPatterns.ORDER_LABELS +
            listOf(
                "账单管理", "商品说明", "支付奖励", "标签", "备注", "计入收支", "为您推荐",
                // 支付宝红包账单详情页（2026-10-17 真机）：这些是字段标签 / 求助入口，不是商户名
                "红包说明", "对此订单有疑问"
            )
        return labels.any { text == it || text.startsWith("$it ") }
    }

    /**
     * 商户名排除用的**句式**（页面提示语与操作文案）。
     *
     * ⚠️ 真机实测（微信 8.0.79 / HyperOS，转账支付成功页）：该页没有任何商户标签，
     * 兜底逻辑会把提示语「待李四确认收款」当成商户名写进账本 —— 转账给个人本来就没有商户，
     * 正确结果是**留空**。同类文案还有「向李四转账」「请验证指纹」「使用密码」「已选择零钱」。
     */
    private val MERCHANT_NOISE_PATTERNS = listOf(
        Regex("^待.{0,10}确认(收款|付款)"),
        Regex("^向.{1,15}转账"),
        Regex("^转账给"),
        Regex("^(请)?(验证|输入|使用)(指纹|密码|面容|支付密码)"),
        Regex("^已选择"),
    )

    internal fun isUsableMerchant(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..20) return false
        // 列表项时间（`20:36`）不是商户名（真机 2026-10-17 微信首页会话列表）
        if (AutoBookPatterns.CLOCK.matches(trimmed)) return false
        if (MERCHANT_NOISE_PATTERNS.any { it.containsMatchIn(trimmed) }) return false
        // 品牌名不是商户（「微信支付」「支付宝」这类在支付页到处都是）
        if (AutoBookPatterns.BRAND_WORDS.any { trimmed == it }) return false
        if (trimmed.all { it.isDigit() || it == '.' || it == ',' || it == '-' }) return false
        if (AutoBookPatterns.SYMBOL_AMOUNT.containsMatchIn(trimmed) || AutoBookPatterns.YUAN_AMOUNT.containsMatchIn(trimmed)) return false
        if (AutoBookPatterns.DATE_TIME.containsMatchIn(trimmed)) return false
        // 真机踩坑：首页渲染中间态会把一串 UI 文案拼成
        // 「上海 搜索框 搜索服务或小程序 搜索 更多操作」当作商户名。
        // 中文商户名几乎不含空格，多个空格一律判为拼接产物（只 1 个空格的中英文名如
        // `Starbucks Coffee` 仍然放行）。
        if (trimmed.count { it == ' ' } >= 2) return false
        val noise = listOf(
            "支付成功", "付款成功", "交易成功", "交易失败", "完成", "详情", "查看", "确认", "全部账单",
            "账单详情", "立即领取", "联系商家", "更多", "请选择", "添加",
            "松开刷新", "下拉刷新", "搜索", "刷新", "扫一扫", "收付款", "卡包", "首页", "我的", "消息", "操作",
            // 支付宝结果页的字段标签 / 收尾按钮，不是商户
            "交易方式", "回首页",
            // 真机实测（微信支付链路各页）：这些是页面按钮 / 状态文案，兜底逻辑会把它们当商户名
            "返回", "关闭", "取消", "支付", "付款", "收款", "转账",
            // 红包结果页上「微信红包」是产品名，不是收款方（真机实测该页没有商户字段）
            "微信红包", "支付宝红包",
            // 支付宝红包账单详情页的状态行（2026-10-17 真机）：`领取中` 不是商户名，
            // 详情页本身没有收款方字段，补全时应保留记账时的商户（如「张三」）。
            "领取中", "已领取", "待领取", "未领取", "已退回", "已过期"
        )
        return noise.none { trimmed == it || trimmed.startsWith(it) }
    }

    /**
     * 分类：平台自带分类优先（支付宝「日用百货」比关键词更准），其次商户关键词，最后一级兜底。
     *
     * 返回的是**分类表里的名字**：能细分时是二级名（「电商」「外卖」「打车」），
     * 平台分类粗到无法细分时是一级名（「交通」「旅游」）；支出兜底「其他」、收入兜底「其他收入」。
     * 映射表写错字时会静默回退兜底一级，保证 [AutoBookDraft.categoryName] 不游离在分类表外。
     */
}
