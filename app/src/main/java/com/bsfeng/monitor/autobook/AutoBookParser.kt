package com.bsfeng.monitor.autobook

import java.security.MessageDigest

/**
 * 一笔待确认的自动记账草稿。
 *
 * 这是「页面 → 浮窗」之间的唯一载体：解析器负责填满它，浮窗负责展示与修改，
 * 用户点「记一笔」后才转成 [com.bsfeng.monitor.finance.model.Transaction] 落库。
 *
 * @param type 0=支出, 1=收入（与 [com.bsfeng.monitor.finance.model.Transaction.type] 同口径；
 *             浮窗还允许用户改成 2=转账）
 * @param accountName 账户名：优先取页面上的付款方式（如「余额宝」），取不到用 App 默认账户
 * @param timeText 页面上的交易时间原文，浮窗直接展示
 * @param sourceId 幂等键：同一笔交易重复识别只落一条
 */
data class AutoBookDraft(
    val channel: String,
    val type: Int,
    val amount: Double,
    val merchant: String,
    val categoryName: String,
    /** 入账账户：取 App 账户体系里的名字（支付宝 / 微信钱包），保证记账能算进余额 */
    val accountName: String,
    /** 页面上的付款方式（余额宝 / 零钱 / 某银行卡），仅作展示与对账线索 */
    val payMethod: String,
    val timeText: String,
    val sourceId: String,
    val markers: List<String>
) {
    /** 来源显示名：支付宝 / 微信 */
    val channelLabel: String
        get() = when (channel) {
            AutoBookRules.CHANNEL_ALIPAY -> "支付宝"
            AutoBookRules.CHANNEL_WECHAT -> "微信"
            else -> channel
        }

    /** 类型显示名，浮窗标题用「识别到一笔支出」 */
    val typeLabel: String
        get() = when (type) {
            TYPE_INCOME -> "收入"
            TYPE_TRANSFER -> "转账"
            else -> "支出"
        }

    val isIncome: Boolean get() = type == TYPE_INCOME

    /** 浮窗备注：来源 + 商户 */
    fun remark(): String =
        if (merchant.isBlank()) "自动记账·$channelLabel" else "自动记账·$merchant"

    companion object {
        const val TYPE_EXPENSE = 0
        const val TYPE_INCOME = 1
        const val TYPE_TRANSFER = 2
    }
}

/**
 * 自动记账解析器：把「一屏视觉行文本」解析成 [AutoBookDraft]。
 *
 * 全部是纯函数（输入文本行 + 时间戳，输出草稿），不触碰无障碍节点，可直接用 JVM 单测覆盖。
 *
 * 规则来源：真机实测页面（支付宝账单详情页）+ 开源实现的通知文本样例，要点：
 * - 金额带方向前缀优先（`支出3元`），比裸数字可靠；
 * - 判定方向前先清洗品牌名（`支付宝`/`微信支付` 含「支付」二字，会误判成支出）；
 * - 未来时态（`将于`/`即将`/`待扣`）一律不记 —— 宁可漏记不可记错；
 * - 标签取值走「同一行的右侧内容」，找不到再退到下一行。
 */
object AutoBookParser {

    /** 同金额同商户的合并时间窗：5 分钟内的重复识别视为同一笔 */
    const val DEDUP_WINDOW_MS = 5 * 60 * 1000L

    /** 带方向前缀的金额：`支出3元` / `已支付 ¥12.34` / `收入 5.00 元` */
    private val PREFIXED_AMOUNT = Regex(
        "(支出|收入|转出|转入|消费|收款|付款|支付|已支付|已收款|扣款|还款)\\s*[¥￥]?\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)\\s*元?"
    )

    /** 带货币符号的金额（微信通知只认这种） */
    private val SYMBOL_AMOUNT = Regex("[¥￥]\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)")

    /** 带「元」的金额（支付宝通知常见） */
    private val YUAN_AMOUNT = Regex("([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)\\s*元")

    /** 裸金额：仅用于详情页的大号金额（如 `-3.00`），必须带两位小数 */
    private val PLAIN_AMOUNT = Regex("(?:^|\\s)(-?)([0-9]{1,3}(?:,[0-9]{3})*\\.[0-9]{2})(?:\\s|$)")

    /** 明确的收入语义 */
    private val INCOME_WORDS = listOf("收款", "到账", "收入", "退款", "已收", "退还", "返现", "入账")

    /** 明确的支出语义 */
    private val EXPENSE_WORDS = listOf("付款", "支付", "消费", "转出", "已付", "扣款", "还款", "支出", "提现")

    /** 品牌名清洗表：这些词含「支付/收款」二字，判定方向前先抹掉（否则「支付宝 | 20元」被记成支出） */
    private val BRAND_WORDS = listOf("支付宝", "微信支付", "微信", "云闪付", "财付通", "银联", "数字人民币")

    /** 未来时态：命中即放弃整条（活动预告、待扣款提醒都不是已发生的交易） */
    private val FUTURE_WORDS = listOf("将于", "即将", "预计", "明日", "待扣", "待支付", "续费提醒", "请及时", "最后提醒")

    /**
     * 账户间转移：提现/充值不是收支，记了会让总身家凭空变化。
     *
     * ⚠️ 必须用**贴住金额的完整词组**，不能用「提现」两个字：支付宝收款通知的尾巴上就带着
     * 「享免费提现等更多专属服务」，只判两个字会把真实收款整条丢掉（有单测覆盖）。
     */
    private val TRANSFER_SKIP_WORDS = listOf(
        "提现到", "提现成功", "提现金额", "提现-", "充值成功", "充值到账", "转入零钱通", "转出到余额"
    )

    /**
     * 非交易文本：验证码、营销、优惠券等。
     *
     * ⚠️ 只允许在**金额所在行**上判定，不能拿来做整页排除 —— 真机支付宝账单详情页底部就有
     * 「支付奖励 立即领取2积分」，整页排除会让一笔真实支出被漏掉。
     */
    private val NOISE_WORDS = listOf("验证码", "优惠券", "立减", "满减", "广告", "恭喜获得")

    /** 金额候选要排除的上下文（余额/额度/优惠不是交易金额） */
    private val AMOUNT_NOISE = listOf(
        "余额", "额度", "限额", "可用", "积分", "优惠", "抵扣", "手续费", "剩余", "冻结", "红包", "立减", "原价"
    )

    /** 商户名标签：命中后取同一行右侧内容 */
    private val MERCHANT_LABELS = listOf(
        "收款方全称", "商户全称", "商户名称", "收款方", "付款方", "交易对方", "对方", "商家", "店铺", "商户"
    )

    /**
     * 账户（付款方式）标签。
     *
     * ⚠️ 不要把「零钱」「余额宝」这类**值**放进来：标签表同时用于「排除值看起来像标签」的判断，
     * 放进来会让「支付方式 零钱」取不到值（真机/单测都踩过）。
     */
    private val ACCOUNT_LABELS = listOf("付款方式", "支付方式", "付款账户", "支付账户", "扣款方式", "付款渠道")

    /** 交易时间标签 */
    private val TIME_LABELS = listOf("支付时间", "交易时间", "付款时间", "创建时间", "转账时间")

    /** 平台分类标签（支付宝/微信自带分类，如「日用百货」） */
    private val CATEGORY_LABELS = listOf("账单分类", "交易分类", "分类")

    /** 订单号标签 */
    private val ORDER_LABELS = listOf("订单号", "交易单号", "商户单号", "交易号", "流水号", "账单号", "业务交易号")

    private val ORDER_ID = Regex("[A-Za-z0-9]{12,}")

    private val DATE_TIME = Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?")

    /** 平台分类 → 本 App 分类 的映射（取不到就按商户关键词猜，再兜底「其他」） */
    private val PLATFORM_CATEGORY_MAP = listOf(
        "餐饮" to listOf("餐饮", "美食", "餐厅", "外卖"),
        "出行" to listOf("交通", "出行", "加油", "打车", "停车"),
        "购物" to listOf("购物", "电商", "商城", "超市"),
        "日用" to listOf("日用", "百货", "生活服务", "家居", "居家"),
        "通讯" to listOf("通讯", "话费", "物流", "快递"),
        "衣物" to listOf("服饰", "装扮", "衣"),
        "美妆" to listOf("美妆", "化妆", "美容"),
        "手机数码" to listOf("数码", "电器", "电子"),
        "就医" to listOf("医疗", "健康", "医药"),
        "休闲娱乐" to listOf("休闲", "娱乐", "文化", "运动", "游戏"),
        "教育" to listOf("教育", "培训", "学习"),
        "房租还贷" to listOf("居住", "物业", "房租", "房", "还款"),
        "旅游度假" to listOf("旅游", "度假", "酒店", "住宿"),
        "出行" to listOf("汽车", "交通")
    )

    /** 商户关键词 → 本 App 分类 */
    private val MERCHANT_CATEGORY_KEYWORDS = listOf(
        "餐饮" to listOf("餐", "饭", "麦当劳", "肯德基", "星巴克", "瑞幸", "咖啡", "奶茶", "烧烤", "火锅", "食"),
        "出行" to listOf("加油", "中石化", "中石油", "停车", "高速", "etc"),
        "打车" to listOf("滴滴", "打车", "曹操", "t3"),
        "地铁火车" to listOf("地铁", "公交", "铁路", "12306", "火车"),
        "购物" to listOf("淘宝", "天猫", "京东", "拼多多", "唯品会", "商场", "超市", "便利店", "小米"),
        "零食" to listOf("零食", "良品铺子", "三只松鼠"),
        "水果" to listOf("水果", "百果园"),
        "日用" to listOf("日用", "百货", "名创优品", "阿里云", "腾讯云", "华为云", "云服务"),
        "通讯" to listOf("话费", "流量", "联通", "移动", "电信", "宽带"),
        "水电" to listOf("水费", "电费", "燃气", "物业", "国家电网"),
        "就医" to listOf("医院", "药", "诊所", "体检"),
        "休闲娱乐" to listOf("电影", "影院", "ktv", "游戏", "会员", "视频", "音乐"),
        "住宿" to listOf("酒店", "民宿", "华住", "如家"),
        "教育" to listOf("教育", "培训", "学费", "书店"),
        "美妆" to listOf("美妆", "化妆", "屈臣氏", "丝芙兰"),
        "衣物" to listOf("服饰", "优衣库", "鞋"),
        "房租还贷" to listOf("房租", "租金", "房贷", "花呗", "白条", "信用卡还款")
    )

    /**
     * 解析一屏视觉行文本。
     *
     * @param rule 命中的 App 规则（包名已匹配）
     * @param lines 视觉行文本（[NodeTextCollector.toLines] 的产物）
     * @param nowMs 事件时间戳，用于去重时间窗与无订单号时的幂等键
     * @return 识别到的一笔账；页面上没有交易信号时返回 null（不弹浮窗）
     */
    fun parse(rule: AutoBookRule, lines: List<String>, nowMs: Long): AutoBookDraft? {
        if (lines.isEmpty()) return null

        val markers = rule.pageMarkers.filter { marker -> lines.any { it.contains(marker) } }
        if (markers.isEmpty()) return null
        if (rule.excludeMarkers.any { marker -> lines.any { it.contains(marker) } }) return null

        val joined = lines.joinToString(" ")
        // 未来时态（活动预告、待扣款提醒）不是已发生的交易，整条放弃
        if (FUTURE_WORDS.any { joined.contains(it) }) return null

        val amount = extractAmount(lines) ?: return null
        if (amount.second <= 0.0) return null
        // 提现/充值属于账户间转移，记了会让总身家凭空变化，不记
        if (TRANSFER_SKIP_WORDS.any { amount.first.contains(it) }) return null

        val type = detectType(lines, amount.first)
        val merchant = extractMerchant(lines)
        val payMethod = extractAccount(lines).orEmpty()
        val timeText = extractTime(lines) ?: ""
        val platformCategory = labelValue(lines, CATEGORY_LABELS)
        val category = mapCategory(platformCategory, merchant, type)
        val orderId = extractOrderId(lines)
        val sourceId = buildSourceId(rule.channel, type, amount.second, merchant, orderId, nowMs)

        return AutoBookDraft(
            channel = rule.channel,
            type = type,
            amount = amount.second,
            merchant = merchant,
            categoryName = category,
            accountName = rule.accountName,
            payMethod = payMethod,
            timeText = timeText,
            sourceId = sourceId,
            markers = markers
        )
    }

    /**
     * 提取金额。
     *
     * 返回 `(命中的原始文本, 金额)`：原始文本用于判定方向（`支出3元` 里的「支出」）。
     * 优先级：带方向前缀 > 带货币符号 > 带「元」 > 裸两位小数（详情页大号金额）。
     */
    fun extractAmount(lines: List<String>): Pair<String, Double>? {
        // 1) 带方向前缀：最可靠，同时给出方向线索
        for (line in lines) {
            if (isAmountNoise(line)) continue
            val m = PREFIXED_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[2])?.let { return line to it }
        }
        // 2) 带货币符号
        for (line in lines) {
            if (isAmountNoise(line)) continue
            val m = SYMBOL_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[1])?.let { return line to it }
        }
        // 3) 带「元」
        for (line in lines) {
            if (isAmountNoise(line)) continue
            val m = YUAN_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[1])?.let { return line to it }
        }
        // 4) 裸两位小数（支付宝/微信详情页大字金额，如 -3.00）
        for (line in lines) {
            if (isAmountNoise(line)) continue
            if (line.contains('元') || line.contains('¥') || line.contains('￥')) continue
            val m = PLAIN_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[2])?.let { return line to it }
        }
        return null
    }

    /** 金额候选是否落在噪声上下文里（余额/额度/手续费/营销文案…） */
    private fun isAmountNoise(line: String): Boolean =
        AMOUNT_NOISE.any { line.contains(it) } || NOISE_WORDS.any { line.contains(it) }

    private fun toAmount(raw: String): Double? =
        raw.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 && it < 10_000_000 }

    /**
     * 判定收支方向。
     *
     * 顺序很重要（借鉴开源实现的经验）：
     * 1. 金额所在行自带方向词（`支出3元`）—— 直接采信；
     * 2. 先查收入词再查支出词 —— 「转账」「红包」本身无方向，收入短语更明确；
     * 3. 判定前清洗品牌名，避免「支付宝」的「支付」二字把收入记成支出；
     * 4. 都没命中时按支出处理：宁可让用户改一次类型，也不要漏记一笔支出。
     */
    fun detectType(lines: List<String>, amountLine: String): Int {
        val amountLineClean = cleanBrand(amountLine)
        if (INCOME_WORDS.any { amountLineClean.contains(it) }) return AutoBookDraft.TYPE_INCOME
        if (EXPENSE_WORDS.any { amountLineClean.contains(it) }) return AutoBookDraft.TYPE_EXPENSE

        val joined = cleanBrand(lines.joinToString(" "))
        val incomeIndex = INCOME_WORDS.map { joined.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val expenseIndex = EXPENSE_WORDS.map { joined.indexOf(it) }.filter { it >= 0 }.minOrNull()
        return when {
            incomeIndex != null && expenseIndex != null ->
                if (incomeIndex <= expenseIndex) AutoBookDraft.TYPE_INCOME else AutoBookDraft.TYPE_EXPENSE
            incomeIndex != null -> AutoBookDraft.TYPE_INCOME
            else -> AutoBookDraft.TYPE_EXPENSE
        }
    }

    private fun cleanBrand(text: String): String {
        var result = text
        for (brand in BRAND_WORDS) {
            result = result.replace(brand, "▢")
        }
        return result
    }

    /**
     * 提取商户名：先按标签取同行右侧内容，取不到再退到页面顶部的标题行。
     *
     * 真机实测：支付宝详情页顶部是居中大标题（商户简称「阿里云」），
     * 表格里另有「收款方全称 → 阿里云计算有限公司」，**优先取全称**（更准确，便于对账）。
     */
    fun extractMerchant(lines: List<String>): String {
        for (label in MERCHANT_LABELS) {
            val value = labelValue(lines, listOf(label))
            if (value != null && isUsableMerchant(value)) return value.take(40)
        }
        // 兜底：页面靠前的、像商户名的短文本
        for (line in lines) {
            if (isLabelLike(line)) continue
            if (isUsableMerchant(line)) return line.take(40)
        }
        return ""
    }

    /** 账户：优先页面上的付款方式（余额宝/零钱/某银行卡） */
    fun extractAccount(lines: List<String>): String? {
        val value = labelValue(lines, ACCOUNT_LABELS) ?: return null
        val trimmed = value.trim()
        // 「付款方式」标签自身会被误取，过滤掉纯标签值
        if (trimmed.isEmpty() || ACCOUNT_LABELS.contains(trimmed)) return null
        return trimmed.take(20)
    }

    /** 交易时间：优先标签行的值，其次全页首个日期时间串 */
    fun extractTime(lines: List<String>): String? {
        for (label in TIME_LABELS) {
            val value = labelValue(lines, listOf(label))
            if (value != null) {
                DATE_TIME.find(value)?.let { return it.value }
            }
        }
        for (line in lines) {
            DATE_TIME.find(line)?.let { return it.value }
        }
        return null
    }

    /** 订单号：命中标签后取同行/下一行的长字母数字串 */
    fun extractOrderId(lines: List<String>): String {
        for (label in ORDER_LABELS) {
            val value = labelValue(lines, listOf(label)) ?: continue
            ORDER_ID.find(value)?.let { return it.value }
        }
        return ""
    }

    /**
     * 取「标签 → 值」：先看同一行里标签后面的内容（`付款方式 余额宝` → 余额宝），
     * 同行没有值就取下一行（`付款方式` 换行 `余额宝`）。
     *
     * @return 取到的值；标签不存在或取不到值时返回 null
     */
    fun labelValue(lines: List<String>, labels: List<String>): String? {
        for (label in labels) {
            for (index in lines.indices) {
                val line = lines[index]
                val at = line.indexOf(label)
                if (at < 0) continue

                // 同行右侧：去掉标签本身与冒号
                val inline = line.substring(at + label.length)
                    .trim()
                    .trimStart('：', ':', ' ', '　')
                    .trim()
                if (inline.isNotEmpty() && !isLabelLike(inline)) return inline

                // 下一行
                val next = lines.getOrNull(index + 1)?.trim()
                if (!next.isNullOrEmpty() && !isLabelLike(next)) return next
            }
        }
        return null
    }

    private fun isLabelLike(text: String): Boolean {
        val labels = MERCHANT_LABELS + ACCOUNT_LABELS + TIME_LABELS + CATEGORY_LABELS + ORDER_LABELS +
            listOf("账单管理", "商品说明", "支付奖励", "标签", "备注", "计入收支", "为您推荐")
        return labels.any { text == it || text.startsWith("$it ") }
    }

    private fun isUsableMerchant(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..40) return false
        if (trimmed.all { it.isDigit() || it == '.' || it == ',' || it == '-' }) return false
        if (SYMBOL_AMOUNT.containsMatchIn(trimmed) || YUAN_AMOUNT.containsMatchIn(trimmed)) return false
        if (DATE_TIME.containsMatchIn(trimmed)) return false
        val noise = listOf(
            "支付成功", "付款成功", "交易成功", "交易失败", "完成", "详情", "查看", "确认", "全部账单",
            "账单详情", "立即领取", "联系商家", "更多", "请选择", "添加"
        )
        return noise.none { trimmed == it || trimmed.startsWith(it) }
    }

    /**
     * 分类：平台自带分类优先（支付宝「日用百货」比关键词更准），其次商户关键词，最后「其他」。
     */
    fun mapCategory(platformCategory: String?, merchant: String, type: Int): String {
        if (!platformCategory.isNullOrBlank()) {
            for ((target, keywords) in PLATFORM_CATEGORY_MAP) {
                if (keywords.any { platformCategory.contains(it) }) return target
            }
        }
        if (merchant.isNotBlank()) {
            val lower = merchant.lowercase()
            for ((target, keywords) in MERCHANT_CATEGORY_KEYWORDS) {
                if (keywords.any { lower.contains(it) }) return target
            }
        }
        return "其他"
    }

    /**
     * 生成幂等键：有订单号用订单号（最精确），否则用「渠道 + 方向 + 金额 + 商户 + 5 分钟桶」，
     * 保证同一页面反复触发事件也只弹一次。
     */
    fun buildSourceId(
        channel: String,
        type: Int,
        amount: Double,
        merchant: String,
        orderId: String,
        nowMs: Long
    ): String {
        val seed = if (orderId.isNotEmpty()) {
            "order:$channel:$orderId"
        } else {
            val window = nowMs / DEDUP_WINDOW_MS
            "win:$channel:$type:$amount:$merchant:$window"
        }
        return md5(seed)
    }

    /** MD5 十六进制摘要（与项目其它幂等键实现同一种写法） */
    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) builder.append('0')
            builder.append(Integer.toHexString(value))
        }
        return builder.toString()
    }
}
