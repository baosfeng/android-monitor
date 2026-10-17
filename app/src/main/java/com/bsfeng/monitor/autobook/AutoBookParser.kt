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
 * @param sourceId **账单身份键**：短期去重（[AutoBookDedup]）与落库幂等（finance_transactions）共用，
 *                 同一笔交易在短时间内重复识别只弹一次、只落一条。
 *                 生成规则见 [AutoBookParser.buildSourceId]，**不含当前时间**，跨时间稳定。
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
    /** 页面上的商品说明（「某某湘菜馆(示例店)外卖订单」），对账时信息量最大 */
    val productDesc: String = "",
    /** 页面上的订单号 / 交易单号原文，跨源对账用 */
    val orderId: String = "",
    /** 平台自带的分类原文（「餐饮美食」），用于校准我们自己的分类映射 */
    val platformCategory: String = "",
    val timeText: String,
    val sourceId: String,
    /** 弹窗去重指纹（多道，任一命中即视为「这笔刚处理过」），见 [AutoBookParser.buildDedupKeys] */
    val dedupKeys: List<DedupKey>,
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

    /**
     * 账本备注：把页面上**能拿到的信息尽量带上** —— 商户 + 商品说明 + 付款方式（不带任何前缀）。
     *
     * 真机反馈「详情信息没记录正确」：原来备注只写商户名，而支付宝账单详情页里
     * 商品说明（`某某湘菜馆(示例店)外卖订单`）与付款方式
     * （`招商银行信用卡(8888)`）这些对账时最有用的信息**全部丢失**。
     *
     * 按信息量降序拼接，只有超出 [REMARK_MAX_LENGTH] 才截断：
     * 商户名取不到时用商品说明顶上，两者都没有才退回渠道名。
     */
    fun remark(): String {
        val parts = mutableListOf<String>()
        val main = merchant.ifBlank { productDesc }
        if (main.isNotBlank()) parts += main
        if (productDesc.isNotBlank() && productDesc != main) parts += productDesc
        if (payMethod.isNotBlank()) parts += payMethod
        if (parts.isEmpty()) parts += channelLabel
        return parts.joinToString("·").take(REMARK_MAX_LENGTH)
    }

    companion object {
        const val TYPE_EXPENSE = 0
        const val TYPE_INCOME = 1
        const val TYPE_TRANSFER = 2

        /** 备注长度上限：账本列表里一行能看清的宽度；超出只截断尾部，主要信息都在前面 */
        const val REMARK_MAX_LENGTH = 80
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

    /**
     * 落库幂等键的兜底时间窗：既没订单号也没交易时间时，5 分钟内的重复识别视为同一笔。
     *
     * ⚠️ 这个时间桶**只用于落库防重**，不参与弹窗去重 —— 桶边界会让同一笔跨桶换键，
     * 正是改造前「同一笔反复弹窗」的根因（弹窗去重见 [buildDedupKeys]）。
     */
    const val FALLBACK_WINDOW_MS = 5 * 60 * 1000L

    /** 精确指纹（订单号 / 交易时间）的安静期：10 分钟内同一笔不再弹 */
    const val PRECISE_KEY_TTL_MS = 10 * 60 * 1000L

    /** 兜底指纹（渠道 + 方向 + 金额）的安静期：短窗口，避免吞掉同金额的另一笔交易 */
    const val FUZZY_KEY_TTL_MS = 2 * 60 * 1000L

    /** 一屏出现多少处交易痕迹就判定为「列表页」（单笔账单页只会出现 1 处） */
    private const val MULTI_TRANSACTION_THRESHOLD = 2

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

    /**
     * 商户名标签：命中后取同一行右侧内容。
     *
     * ⚠️ 这里**不放「付款方」**：它是「付款方式」的前缀，会从 `付款方式 招商银行信用卡(8888)`
     * 这行取出「式 招商银行信用卡(8888)」当商户名（真机实测踩到）。而且付款方是转账的转出方，
     * 本来也不是商户。
     */
    private val MERCHANT_LABELS = listOf(
        "收款方全称", "商户全称", "商户名称", "收款方", "交易对方", "对方", "商家", "店铺", "商户"
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

    /**
     * 商品说明标签。
     *
     * 真机实测（支付宝账单详情页）：`商品说明 某某湘菜馆(示例店)外卖订单` ——
     * 这是整页**对账信息量最大**的字段（商户名常常只是平台名，如「淘宝闪购」）。
     * 顺序有讲究：`商品说明` 必须排在 `商品` 前面，否则被短标签抢占。
     */
    private val PRODUCT_LABELS = listOf("商品说明", "订单说明", "交易说明", "商品")

    /** 商品说明保留长度：商品名可能很长，超出截断 */
    private const val PRODUCT_DESC_MAX_LENGTH = 40

    /** 页面交易时间的两种格式（ISO / 中文年月日），见 [normalizeTradeTime] */
    private val TRADE_TIME_PATTERNS = listOf(
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"),
        Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    )

    /** 订单号标签 */
    private val ORDER_LABELS = listOf("订单号", "交易单号", "商户单号", "交易号", "流水号", "账单号", "业务交易号")

    /**
     * 订单号标签的**行内**出现形式。
     *
     * ⚠️ 真机实测：支付宝账单详情页并不给订单号单独一行，而是把它塞在别的值里 ——
     * `商品说明 充值:阿里云服务购买,业务交易号:CFP000000000000000000`。
     * 只按「标签 → 整行值」取会漏掉，必须再扫一遍行内标签。
     */
    private val ORDER_LABEL_INLINE = Regex(
        "(业务交易号|交易单号|商户单号|订单号|交易号|流水号|账单号)[:：]?"
    )

    private val ORDER_ID = Regex("[A-Za-z0-9]{12,}")

    /**
     * 日期时间串。
     *
     * ⚠️ **两种格式都要认**：支付宝写 `2026-10-15 23:14:43`，微信写 `2026年10月10日 14:01:42`
     * （都是真机原文）。只认前者会让微信详情页取不到交易时间，去重就退化成 2 分钟的金额兜底指纹。
     */
    private val DATE_TIME = Regex(
        "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?" +
            "|\\d{4}年\\d{1,2}月\\d{1,2}日\\s*\\d{1,2}:\\d{2}(?::\\d{2})?"
    )

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
     * 解析一屏视觉行文本（**页面入口**）。
     *
     * ⚠️ **只在账单 / 交易页上才认账**。真机实测（支付宝首页）：首页「最近消息」卡片里的
     * **历史消息摘要** `付款成功￥3.00` 长得和支付结果页一模一样，会被认成一笔新支出，
     * 浮窗直接盖在首页上（用户反馈「识别有误」）。因此这里加了一道页面结构门禁
     * [isBillPage]：要么是有字段标签的账单详情页，要么是有收尾按钮的独占结果页。
     *
     * @param rule 命中的 App 规则（包名已匹配）
     * @param lines 视觉行文本（[PageLines.toLines] 的产物）
     * @param nowMs 事件时间戳，只用于「既无订单号也无交易时间」时的落库幂等兜底
     * @return 识别到的一笔账；页面上没有交易信号、或当前不是账单页时返回 null（不弹浮窗）
     */
    fun parse(rule: AutoBookRule, lines: List<String>, nowMs: Long): AutoBookDraft? =
        parseInternal(rule, lines, nowMs, requireBillPage = true)

    /**
     * 解析通知文本（**通知入口**，供后续接入 NotificationListenerService 使用）。
     *
     * 通知由系统直接投递、来源明确，不存在「首页消息摘要」这类混淆，因此不做页面门禁。
     */
    fun parseNotification(rule: AutoBookRule, lines: List<String>, nowMs: Long): AutoBookDraft? =
        parseInternal(rule, lines, nowMs, requireBillPage = false)

    private fun parseInternal(
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
        if (requireBillPage && !isBillPage(lines)) return null

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
        val productDesc = extractProductDesc(lines)
        val timeText = extractTime(lines) ?: ""
        val platformCategory = labelValue(lines, CATEGORY_LABELS)
        val category = mapCategory(platformCategory, merchant, type)
        val orderId = extractOrderId(lines)
        val sourceId = buildSourceId(rule.channel, type, amount.second, merchant, orderId, timeText, nowMs)
        val dedupKeys = buildDedupKeys(rule.channel, type, amount.second, orderId, timeText)

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

    // ==================== 账单页门禁 ====================

    /**
     * 账单页结构标签：账单 / 交易详情页靠这些「标签 → 值」成对呈现。
     *
     * 命中任意一个**并且取到了值**，就说明当前确实是账单详情页，而不是首页消息流。
     *
     * ⚠️ **不要放「交易详情 / 付款详情 / 转账详情」**：真机实测微信支付**会话页**的支付消息卡片，
     * 会把按钮文字拼进整张卡片的 content-desc —— `使用零钱支付￥43.00交易详情`，
     * 于是「交易详情」被当成字段标签、又顺手取到了下一行的值，会话页就被误判成详情页、当场弹窗。
     * 它们是标题 / 按钮，不是「标签 → 值」字段。
     */
    private val DETAIL_LABELS: List<String> =
        TIME_LABELS + ACCOUNT_LABELS + CATEGORY_LABELS + ORDER_LABELS + MERCHANT_LABELS +
            listOf("商品说明", "交易状态", "当前状态")

    /**
     * 列表 / 摘要页特征词。
     *
     * 真机实测支付宝首页：「最近消息」「还有3条未读服务消息」+ 下拉刷新的「松开刷新」提示，
     * 这些词一出现就说明当前是消息流，而不是账单详情页。
     */
    private val LIST_PAGE_WORDS = listOf(
        "最近消息", "未读服务消息", "松开刷新", "下拉刷新", "上拉加载", "加载更多"
    )

    /** 相对时间（`9小时前` / `2分钟前`）：账单详情页给的是绝对时间，只有列表项才给相对时间 */
    private val RELATIVE_TIME = Regex("(刚刚|\\d+\\s*(分钟|小时|天|周|个月)前)")

    /**
     * **消息流的时间分隔行**（`10月10日 14:01` / `周一 10:29`）—— 微信支付会话页每条消息上方都有一条。
     *
     * ⚠️ 必须**整行锚定**：详情页写的是 `2026年10月10日 14:01:42`，它**包含** `10月10日 14:01` 这个子串，
     * 用 `contains` 判定会把好好的详情页一起否掉。另外详情页的时间行前面还挂着「支付时间」标签，也匹配不上整行锚点。
     */
    private val CHAT_TIME_DIVIDER = Regex(
        "^\\s*(\\d{1,2}月\\d{1,2}日|周[一二三四五六日])\\s+\\d{1,2}:\\d{2}\\s*$"
    )

    /**
     * 红包**发送前 / 付款中**页面的特征词：出现任一即说明这笔还没付出去。
     *
     * 用来把「红包结果页」和「塞钱进红包输入页 / 收银台 / 指纹验证页」区分开 ——
     * 少了这道区分，红包就会在**付款之前**弹窗。
     */
    private val RED_PACKET_PRE_PAY_WORDS = listOf(
        "塞钱进红包", "未领取的红包", "发红包", "使用密码", "请验证指纹", "输入支付密码"
    )

    /** 结果页收尾按钮：独占的支付结果页一定有一个「离开」入口 */
    private val RESULT_PAGE_ACTIONS = listOf("完成", "账单详情", "查看账单", "查看详情", "返回首页", "我知道了")

    /**
     * 整行就是一个状态锚点（`支付成功` / `付款成功￥3.00`）。
     *
     * ⚠️ 注意它**认不出**消息摘要：首页那条历史摘要 `付款成功￥3.00` 单独成行也照样匹配，
     * 所以本锚点只能当必要条件，必须再叠加 [RESULT_PAGE_ACTIONS] 才放行。
     */
    private val STATUS_ANCHOR = Regex(
        "^(支付成功|付款成功|交易成功|转账成功|收款成功|还款成功|退款成功|已支付)[¥￥]?[0-9,.\\s]*元?$"
    )

    /**
     * 单笔交易的状态词：一屏里出现多次，说明这是流水 / 消息列表而不是单笔账单页。
     */
    private val STATUS_WORDS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "收款成功", "还款成功", "退款成功",
        "还款到账成功", "已支付"
    )

    /**
     * 一屏里出现 ≥ [MULTI_TRANSACTION_THRESHOLD] 处多笔交易的痕迹。
     *
     * 真机实测支付宝「支付消息」列表：每张卡片都自带一份「付款方式」，也各自有「付款成功」——
     * 只看「有没有详情标签」会把它误判成账单详情页，而且列表第一条的金额商户**恰好也能提取出来**，
     * 于是弹出一个「看起来是对的」的浮窗。所以多笔信号必须**优先于**详情结构判定。
     */
    private fun hasMultipleTransactions(lines: List<String>): Boolean {
        val statusHits = lines.count { line -> STATUS_WORDS.any { line.contains(it) } }
        if (statusHits >= MULTI_TRANSACTION_THRESHOLD) return true
        val labelHits = lines.count { line -> ACCOUNT_LABELS.any { line.contains(it) } }
        if (labelHits >= MULTI_TRANSACTION_THRESHOLD) return true
        // 单笔详情页一屏只有一个金额；出现两个就说明这是消息流 / 流水列表
        // （真机：微信支付会话页同屏有 ￥43.00 与 ￥26.00 两条消息卡片）
        return countAmountLines(lines) >= MULTI_TRANSACTION_THRESHOLD
    }

    /**
     * 一屏里的金额候选数。
     *
     * 只数**带货币符号或带「元」**的金额，不数裸小数（`-43.00` 是详情页大号金额专用的形态），
     * 并且跳过余额 / 优惠 / 手续费等上下文（[isAmountNoise]），避免详情页被自己的优惠行误伤。
     */
    private fun countAmountLines(lines: List<String>): Int =
        lines.count { line ->
            !isAmountNoise(line) &&
                (SYMBOL_AMOUNT.containsMatchIn(line) || YUAN_AMOUNT.containsMatchIn(line))
        }

    /**
     * 当前页面是不是**单笔**账单 / 交易页。
     *
     * 判定顺序（顺序本身就是设计）：
     * 1. **多笔痕迹** → 拒（流水列表 / 支付消息列表，哪怕它带了详情标签）；
     * 2. **账单详情页** → 放行（有「标签 → 值」结构，支付宝账单详情页就是这种）；
     * 3. **列表 / 摘要页** → 拒（首页「最近消息」的历史消息摘要靠这条挡掉）；
     * 4. **独占结果页** → 放行（整行状态锚点 + 收尾按钮，付款后立即跳出的那页）；
     * 5. **微信红包结果页** → 放行（红包没有锚点页，见 [isRedPacketResultPage]）。
     *
     * 公开给 [AutoBookEngine] 做诊断用：识别失败时要能区分「门禁拒绝」和「是账单页但字段没认出来」。
     */
    fun isBillPage(lines: List<String>): Boolean {
        if (hasMultipleTransactions(lines)) return false
        if (hasDetailStructure(lines)) return true
        if (isListOrSummaryPage(lines)) return false
        if (isStandaloneResultPage(lines)) return true
        return isRedPacketResultPage(lines)
    }

    /** 有任意一个账单字段标签取到了值 */
    private fun hasDetailStructure(lines: List<String>): Boolean =
        DETAIL_LABELS.any { label -> labelValue(lines, listOf(label)) != null }

    private fun isListOrSummaryPage(lines: List<String>): Boolean =
        lines.any { line ->
            LIST_PAGE_WORDS.any { line.contains(it) } ||
                RELATIVE_TIME.containsMatchIn(line) ||
                CHAT_TIME_DIVIDER.matches(line.trim())
        }

    private fun isStandaloneResultPage(lines: List<String>): Boolean {
        val hasAnchor = lines.any { STATUS_ANCHOR.matches(it.trim()) }
        if (!hasAnchor) return false
        return lines.any { line -> RESULT_PAGE_ACTIONS.any { line.contains(it) } }
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
     * ⚠️ 必须叠加 [RED_PACKET_PRE_PAY_WORDS] 排除，否则会在**付款之前**弹窗。
     */
    private fun isRedPacketResultPage(lines: List<String>): Boolean {
        val joined = lines.joinToString(" ")
        if (!joined.contains("红包")) return false
        if (!joined.contains("微信支付")) return false
        if (RED_PACKET_PRE_PAY_WORDS.any { joined.contains(it) }) return false
        return extractAmount(lines) != null
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

    /**
     * 商品说明：支付宝账单详情页的「商品说明 → 具体商品/订单」。
     *
     * 商户名在线上支付场景常常只是平台名（真机：顶部大标题是「淘宝闪购」），
     * 真正说明这笔钱花在哪的是商品说明，因此它要进账本备注。
     */
    fun extractProductDesc(lines: List<String>): String {
        val value = labelValue(lines, PRODUCT_LABELS)?.trim().orEmpty()
        if (value.isEmpty() || PRODUCT_LABELS.contains(value)) return ""
        return value.take(PRODUCT_DESC_MAX_LENGTH)
    }

    /** 账户：优先页面上的付款方式（余额宝/零钱/某银行卡） */
    fun extractAccount(lines: List<String>): String? {
        val value = labelValue(lines, ACCOUNT_LABELS) ?: return null
        var method = value.trim().trimStart('，', ',', '、', ';', '；', ' ')
        // 「付款方式」标签自身会被误取，过滤掉纯标签值
        if (method.isEmpty() || ACCOUNT_LABELS.contains(method)) return null

        // 微信收银台 / 支付结果页把付款方式渲染成「已选择零钱，更改」——
        // 「更改」是一个可点入口，跟着进浮窗和账本备注就会变成「，已选择零钱，更改」脏值。
        // 展示层只要「零钱」：去掉「已选择」前缀与「更改」尾巴，再去掉残留标点。
        method = method.removePrefix("已选择").trim()
        method = method.substringBefore("更改").trim()
            .trimEnd('，', ',', '、', ';', '；', ' ')
        if (method.isEmpty() || ACCOUNT_LABELS.contains(method)) return null
        return method.take(20)
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

    /**
     * 订单号：先按独立标签取，再扫行内标签。
     *
     * ⚠️ 真机实测：支付宝账单详情页并不给订单号单独一行，而是把它塞在别的值里 ——
     * `商品说明 充值:阿里云服务购买,业务交易号:CFP000000000000000000`。
     * 订单号一漏，去重就退化成「金额 + 商户」的模糊匹配，而商户名在渲染中间态下会抖。
     */
    fun extractOrderId(lines: List<String>): String {
        for (label in ORDER_LABELS) {
            val value = labelValue(lines, listOf(label)) ?: continue
            ORDER_ID.find(value)?.let { return it.value }
        }
        for (line in lines) {
            val match = ORDER_LABEL_INLINE.find(line) ?: continue
            val tail = line.substring(match.range.last + 1)
            ORDER_ID.find(tail)?.let { return it.value }
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

                // 前缀误命中防护：短标签会命中长标签并取出半截值
                // （「付款方」命中「付款方式」→「式 招商银行信用卡(8888)」；
                //   「收款方」命中「收款方全称」→「全称 阿里云计算有限公司」）。
                // 标签后面紧跟汉字即视为更长词的片段，跳过；紧跟冒号 / 空格 / 字母数字仍然放行。
                val after = at + label.length
                if (after < line.length && isCjkIdeograph(line[after])) continue

                // 同行右侧：去掉标签本身、冒号与可能多出来的逗号
                // （微信收银台原文是 `付款方式,已选择零钱,更改 更改`，逗号会粘在值前面）
                val inline = line.substring(at + label.length)
                    .trim()
                    .trimStart('：', ':', ' ', '　', ',', '，', '、', ';', '；')
                    .trim()
                if (inline.isNotEmpty() && !isLabelLike(inline)) return inline

                // 下一行
                val next = lines.getOrNull(index + 1)?.trim()
                    ?.trimStart(',', '，', '、', ';', '；')
                    ?.trim()
                if (!next.isNullOrEmpty() && !isLabelLike(next)) return next
            }
        }
        return null
    }

    /** 是否是汉字（判断标签后面是否紧跟了更长词的片段；全角标点不算） */
    private fun isCjkIdeograph(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private fun isLabelLike(text: String): Boolean {
        val labels = MERCHANT_LABELS + ACCOUNT_LABELS + TIME_LABELS + CATEGORY_LABELS + ORDER_LABELS +
            listOf("账单管理", "商品说明", "支付奖励", "标签", "备注", "计入收支", "为您推荐")
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

    private fun isUsableMerchant(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..20) return false
        if (MERCHANT_NOISE_PATTERNS.any { it.containsMatchIn(trimmed) }) return false
        // 品牌名不是商户（「微信支付」「支付宝」这类在支付页到处都是）
        if (BRAND_WORDS.any { trimmed == it }) return false
        if (trimmed.all { it.isDigit() || it == '.' || it == ',' || it == '-' }) return false
        if (SYMBOL_AMOUNT.containsMatchIn(trimmed) || YUAN_AMOUNT.containsMatchIn(trimmed)) return false
        if (DATE_TIME.containsMatchIn(trimmed)) return false
        // 真机踩坑：首页渲染中间态会把一串 UI 文案拼成
        // 「上海 搜索框 搜索服务或小程序 搜索 更多操作」当作商户名。
        // 中文商户名几乎不含空格，多个空格一律判为拼接产物（只 1 个空格的中英文名如
        // `Starbucks Coffee` 仍然放行）。
        if (trimmed.count { it == ' ' } >= 2) return false
        val noise = listOf(
            "支付成功", "付款成功", "交易成功", "交易失败", "完成", "详情", "查看", "确认", "全部账单",
            "账单详情", "立即领取", "联系商家", "更多", "请选择", "添加",
            "松开刷新", "下拉刷新", "搜索", "刷新", "扫一扫", "收付款", "卡包", "首页", "我的", "消息", "操作",
            // 真机实测（微信支付链路各页）：这些是页面按钮 / 状态文案，兜底逻辑会把它们当商户名
            "返回", "关闭", "取消", "支付", "付款", "收款", "转账",
            // 红包结果页上「微信红包」是产品名，不是收款方（真机实测该页没有商户字段）
            "微信红包"
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
     * 生成**落库幂等键**（同一笔只写一条记录）。
     *
     * 优先级：订单号 > 交易时间 > 「渠道 + 方向 + 金额 + 商户 + 5 分钟桶」。
     *
     * ⚠️ 最后一档是**故意保留时间桶**的：落库这一侧宁可重复也不要漏账 ——
     * 若拿「渠道 + 方向 + 金额」当唯一键，用户连着买两笔 3 元会被合并成一笔，那是真丢账。
     * 「同一笔反复弹窗」这个痛点由 [buildDedupKeys] + [AutoBookDedup] 单独解决，不靠这个键。
     */
    fun buildSourceId(
        channel: String,
        type: Int,
        amount: Double,
        merchant: String,
        orderId: String,
        timeText: String,
        nowMs: Long
    ): String {
        val seed = when {
            orderId.isNotEmpty() -> "order:$channel:$orderId"
            timeText.isNotEmpty() -> "time:$channel:$type:$amount:$merchant:$timeText"
            else -> {
                val window = nowMs / FALLBACK_WINDOW_MS
                "win:$channel:$type:$amount:$merchant:$window"
            }
        }
        return md5(seed)
    }

    /**
     * 生成**弹窗去重指纹**（多道，任一命中即视为「这笔刚处理过」）。
     *
     * 为什么是多道而不是一个键：页面在渲染中间态下字段会抖 —— 真机实测同一笔在支付宝首页
     * 两次识别出的商户名完全不同（一次「松开刷新」，一次「上海 搜索框 搜索服务或小程序 搜索 更多操作」）。
     * 指纹里只要掺了商户名这种易抖字段，同一笔就会算出两个键，去重失效、浮窗连弹两次。
     *
     * 因此：
     * - **精确指纹**（订单号 / 交易时间）安静期长 —— 它们来自账单自身，跨时间稳定；
     * - **兜底指纹**（渠道 + 方向 + 金额，**刻意不含商户名**）标为 `precise = false` ——
     *   它分不清「同一笔」和「另一笔同金额」，所以只做「刚弹过」的短暂防抖，
     *   **不参与**「已忽略 / 已记账」的长期记忆（否则后一笔同金额的真实消费会被静默吞掉，真机踩过）。
     *
     * @return 至少一条指纹
     */
    fun buildDedupKeys(
        channel: String,
        type: Int,
        amount: Double,
        orderId: String,
        timeText: String
    ): List<DedupKey> {
        val keys = mutableListOf<DedupKey>()
        if (orderId.isNotEmpty()) {
            keys += DedupKey("order:$channel:$orderId", PRECISE_KEY_TTL_MS)
        }
        if (timeText.isNotEmpty()) {
            keys += DedupKey("time:$channel:$type:$amount:$timeText", PRECISE_KEY_TTL_MS)
        }
        keys += DedupKey("sum:$channel:$type:$amount", FUZZY_KEY_TTL_MS, precise = false)
        return keys
    }

    /**
     * 页面交易时间 → `yyyy-MM-dd HH:mm:ss`（**精确到秒**）；解析不出来返回 null。
     *
     * 页面给两种格式（真机实测）：支付宝 `2026-10-16 18:32:21`、微信 `2026年10月16日 18:32:42`。
     *
     * ⚠️ 落库的 [Transaction.date] / [Transaction.dateTime] **必须优先用页面上的交易时间**：
     * 用「点记一笔的时刻」会让同一笔在不同时间被识别时算出不同的落库键（真机就出现过
     * 同一笔 18:32 的交易被记成 20:51、21:00 两条），而且对账时日期是错的。
     */
    fun normalizeTradeTime(timeText: String): String? {
        if (timeText.isBlank()) return null
        for (pattern in TRADE_TIME_PATTERNS) {
            val m = pattern.find(timeText) ?: continue
            // 秒可能缺失（部分模板只给到分），缺失时补 00 —— 精度按页面能给到的最高为准
            return "%04d-%02d-%02d %02d:%02d:%02d".format(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
                m.groupValues[4].toInt(),
                m.groupValues[5].toInt(),
                m.groupValues[6].toIntOrNull() ?: 0
            )
        }
        return null
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
