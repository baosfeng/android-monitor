package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookParser.kt 拆出：金额 / 方向 / 商户 / 商品说明 / 账户 的提取。
// 全部为纯文本规则；改这里等于改识别口径，改前先看 AutoBookParserTest。

/** 金额 / 方向 / 商户 / 商品说明 / 账户提取。 */
internal object AutoBookFieldExtract {

    /**
     * 提取金额。
     *
     * 返回 `(命中的原始文本, 金额)`：原始文本用于判定方向（`支出3元` 里的「支出」）。
     * 优先级：带方向前缀 > 带货币符号 > 带「元」 > 裸两位小数（详情页大号金额）。
     */
    fun extractAmount(lines: List<String>): Pair<String, Double>? {
        // 时间戳不是金额：`微信支付 20:47` 会被「支付」前缀命中、把小时数 20 当成金额
        // （真机 2026-10-17 微信首页「识别出一笔收入 ¥20.00」）。匹配前抹掉时间戳，返回值仍用原文。
        val pairs = lines.map { it to stripClock(it) }
        // 1) 带方向前缀：最可靠，同时给出方向线索
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            val m = AutoBookPatterns.PREFIXED_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[2])?.let { return raw to it }
        }
        // 2) 带货币符号
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            val m = AutoBookPatterns.SYMBOL_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[1])?.let { return raw to it }
        }
        // 3) 带「元」
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            val m = AutoBookPatterns.YUAN_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[1])?.let { return raw to it }
        }
        // 4) 裸两位小数（支付宝/微信详情页大字金额，如 -3.00）
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            if (line.contains('元') || line.contains('¥') || line.contains('￥')) continue
            val m = AutoBookPatterns.PLAIN_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[2])?.let { return raw to it }
        }
        return null
    }

    /** 抹掉时间戳（`20:47` / `23:14:43`），金额匹配只在抹除后的文本上进行 */
    internal fun stripClock(line: String): String = AutoBookPatterns.CLOCK.replace(line, " ")

    /** 金额候选是否落在噪声上下文里（余额/额度/手续费/营销文案…） */
    internal fun isAmountNoise(line: String): Boolean =
        AutoBookPatterns.AMOUNT_NOISE.any { line.contains(it) } || AutoBookPatterns.NOISE_WORDS.any { line.contains(it) }

    internal fun toAmount(raw: String): Double? =
        raw.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 && it < 10_000_000 }

    /**
     * 判定收支方向。
     *
     * 顺序很重要（借鉴开源实现的经验）：
     * 1. 金额所在行自带方向词（`支出3元`）—— 直接采信；
     * 2. 先查收入词再查支出词 —— 「转账」「红包」本身无方向，收入短语更明确；
     * 3. 判定前清洗品牌名与方向标签，避免「支付宝」的「支付」二字 / 「收款方」的「收款」二字把方向判反；
     * 4. 都没命中时按支出处理：宁可让用户改一次类型，也不要漏记一笔支出。
     */
    fun detectType(parser: AutoBookParser, lines: List<String>, amountLine: String): Int {
        val amountLineClean = neutralizeDirectionWords(cleanBrand(amountLine))
        if (AutoBookPatterns.INCOME_WORDS.any { amountLineClean.contains(it) }) return AutoBookDraft.TYPE_INCOME
        if (AutoBookPatterns.EXPENSE_WORDS.any { amountLineClean.contains(it) }) return AutoBookDraft.TYPE_EXPENSE

        val joined = neutralizeDirectionWords(cleanBrand(lines.joinToString(" ")))
        val incomeIndex = AutoBookPatterns.INCOME_WORDS.map { joined.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val expenseIndex = AutoBookPatterns.EXPENSE_WORDS.map { joined.indexOf(it) }.filter { it >= 0 }.minOrNull()
        return when {
            incomeIndex != null && expenseIndex != null ->
                if (incomeIndex <= expenseIndex) AutoBookDraft.TYPE_INCOME else AutoBookDraft.TYPE_EXPENSE
            incomeIndex != null -> AutoBookDraft.TYPE_INCOME
            else -> AutoBookDraft.TYPE_EXPENSE
        }
    }

    private fun cleanBrand(text: String): String {
        var result = text
        for (brand in AutoBookPatterns.BRAND_WORDS) {
            result = result.replace(brand, "▢")
        }
        return result
    }

    /** 抹掉只表达「谁收谁付」的标签词，保留真正的方向语义（见 [AutoBookPatterns.DIRECTION_LABEL_NOISE]）。 */
    private fun neutralizeDirectionWords(text: String): String {
        var result = text
        for (label in AutoBookPatterns.DIRECTION_LABEL_NOISE) {
            result = result.replace(label, "▢")
        }
        return AutoBookPatterns.PENDING_RECEIPT.replace(result, "▢")
    }

    /**
     * 提取商户名：先按标签取同行右侧内容，取不到再退到页面顶部的标题行。
     *
     * 真机实测：支付宝详情页顶部是居中大标题（商户简称「阿里云」），
     * 表格里另有「收款方全称 → 阿里云计算有限公司」，**优先取全称**（更准确，便于对账）。
     */
    fun extractMerchant(parser: AutoBookParser, lines: List<String>): String {
        for (label in AutoBookPatterns.MERCHANT_LABELS) {
            val value = AutoBookFieldParse.labelValue(parser, lines, listOf(label))
            if (value != null && AutoBookFieldParse.isUsableMerchant(value)) return value.take(40)
        }
        // 兜底：页面靠前的、像商户名的短文本
        for (line in lines) {
            if (AutoBookFieldParse.isLabelLike(line)) continue
            if (AutoBookFieldParse.isUsableMerchant(line)) return line.take(40)
        }
        return ""
    }

    /**
     * 商品说明：支付宝账单详情页的「商品说明 → 具体商品/订单」。
     *
     * 商户名在线上支付场景常常只是平台名（真机：顶部大标题是「淘宝闪购」），
     * 真正说明这笔钱花在哪的是商品说明，因此它要进账本备注。
     */
    fun extractProductDesc(parser: AutoBookParser, lines: List<String>): String {
        val value = AutoBookFieldParse.labelValue(parser, lines, AutoBookPatterns.PRODUCT_LABELS)?.trim().orEmpty()
        if (value.isEmpty() || AutoBookPatterns.PRODUCT_LABELS.contains(value)) return ""
        return value.take(AutoBookPatterns.PRODUCT_DESC_MAX_LENGTH)
    }

    /** 账户：优先页面上的付款方式（余额宝/零钱/某银行卡） */
    fun extractAccount(parser: AutoBookParser, lines: List<String>): String? {
        val value = AutoBookFieldParse.labelValue(parser, lines, AutoBookPatterns.ACCOUNT_LABELS) ?: return null
        var method = value.trim().trimStart('，', ',', '、', ';', '；', ' ')
        // 「付款方式」标签自身会被误取，过滤掉纯标签值
        if (method.isEmpty() || AutoBookPatterns.ACCOUNT_LABELS.contains(method)) return null

        // 微信收银台 / 支付结果页把付款方式渲染成「已选择零钱，更改」——
        // 「更改」是一个可点入口，跟着进浮窗和账本备注就会变成「，已选择零钱，更改」脏值。
        // 展示层只要「零钱」：去掉「已选择」前缀与「更改」尾巴，再去掉残留标点。
        method = method.removePrefix("已选择").trim()
        method = method.substringBefore("更改").trim()
            .trimEnd('，', ',', '、', ';', '；', ' ')
        if (method.isEmpty() || AutoBookPatterns.ACCOUNT_LABELS.contains(method)) return null
        // 兜底：值不能是纯数字（「推荐付款方式」后面紧跟的金额行）
        if (method.toDoubleOrNull() != null) return null
        return method.take(20)
    }

    /**
     * 是否是付款前页面（收银台 / 指纹 / 密码输入 / 转账确认）。
     *
     * 注意：它**不是**账单页 —— [isBillPage] 会拒绝它；这里只用于预采集。
     */
}
