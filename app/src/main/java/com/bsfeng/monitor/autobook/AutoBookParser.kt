package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.CategoryCatalog
import java.security.MessageDigest
import kotlin.math.abs
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
    val markers: List<String>,
    /**
     * 用户写的「说明」预填值：付款前页面上填的备注（微信「转账说明」/ 支付宝「备注」）。
     *
     * 结果页通常不展示这行字，只能在付款前页面预采集 —— 见 [PrePaymentDraft]。
     * 浮窗里这行**可编辑**，用户确认时以编辑后的值为准（[bookedRemark]）。
     */
    val userRemark: String = ""
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
     * 账本「说明」：**只返回用户自己写的文字**，不再拼接商户 / 商品说明 / 付款方式。
     *
     * 2026-10-20 用户要求改语义：账本那一列从「页面信息汇总」变成「说明」——
     * 商户 / 商品说明 / 付款方式已经是独立结构化字段（[merchant] / [productDesc] / [payMethod]），
     * 再拼进说明只会让账本列表出现一长串重复文字，用户自己写的那句反而被淹没。
     */
    fun remark(): String = normalizeRemark(userRemark)

    /**
     * 确认落库时的说明：**浮窗里编辑过的值优先**，没传才退回预填的 [userRemark]。
     *
     * 空串（或只有空白）表示用户没写说明 —— 调用方直接写空串，不编造内容。
     */
    fun bookedRemark(editedRemark: String? = null): String =
        normalizeRemark(editedRemark ?: userRemark)

    companion object {
        const val TYPE_EXPENSE = 0
        const val TYPE_INCOME = 1
        const val TYPE_TRANSFER = 2

        /** 说明长度上限：账本列表里一行能看清的宽度；超出只截断尾部，主要信息都在前面 */
        const val REMARK_MAX_LENGTH = 80

        /** 说明归一：去首尾空白 + 截断到 [REMARK_MAX_LENGTH]（空串 = 不写说明） */
        fun normalizeRemark(text: String): String = text.trim().take(REMARK_MAX_LENGTH)
    }
}

/**
 * 付款前页面（转账输入 / 收银台 / 指纹 / 确认付款）上**预采集**到的草稿。
 *
 * 真机背景（2026-10-17）：微信转账的收款方、用户填的「转账说明」只在付款前页面出现；
 * 支付成功页是极简静态页，这些信息拿不到。因此识别到付款前页面时**不弹窗**，只把数据缓存，
 * 等支付成功页出现后由 [AutoBookDraftMerger] 合并。
 */
data class PrePaymentDraft(
    val channel: String,
    val type: Int,
    val amount: Double,
    val merchant: String,
    val payMethod: String,
    val remark: String,
    val capturedAtMs: Long
)

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

    /**
     * 时间戳（`20:47` / `23:14:43`）。
     *
     * ⚠️ 真机事故（2026-10-17 微信首页）：`微信支付 20:47` 这行被 [PREFIXED_AMOUNT] 的
     * 「支付」前缀命中，小时数 `20` 被当成金额 —— 首页于是「识别出一笔收入 ¥20.00」。
     * 金额提取前先抹掉时间戳：时间永远不是金额。
     */
    private val CLOCK = Regex("(?<!\\d)\\d{1,2}:\\d{2}(?::\\d{2})?(?!\\d)")

    /** 明确的收入语义 */
    private val INCOME_WORDS = listOf("收款", "到账", "收入", "退款", "已收", "退还", "返现", "入账")

    /** 明确的支出语义 */
    private val EXPENSE_WORDS = listOf("付款", "支付", "消费", "转出", "已付", "扣款", "还款", "支出", "提现")

    /**
     * 方向词出现在**标签 / 提示语**里时先抹掉，否则整页方向会判反。
     *
     * 真机事故（2026-10-17，支付宝转账成功页）：页面是
     * `转账成功￥0.01 回首页 | 转账成功 | 0.01 | 收款方 张三(***) | 交易方式 余额宝(转出资金付款) | 完成`。
     * 「收款方」是**收款人标签**（这笔钱是用户付出去的），但 [INCOME_WORDS] 里的「收款」先命中、
     * 且位置早于「转出资金付款」里的「付款」，于是整笔被记成**收入**。
     *
     * 这些词只做标签，不表达方向，必须在判定前替换掉。
     */
    private val DIRECTION_LABEL_NOISE = listOf(
        "收款方", "付款方", "收款人", "付款人", "收款账户", "付款账户",
        "付款方式", "收款方式", "交易方式"
    )

    /**
     * 付款方视角的「等待对方确认收款」提示（微信转账成功页：`待李四确认收款`）。
     *
     * 这里面的「收款」描述的是**对方**的动作，不是用户收入；不抹掉会把支出判成收入。
     * ⚠️ 不能只抹「确认收款」：收款方自己点「确认收款」时那是真收入。
     */
    private val PENDING_RECEIPT = Regex("待.{0,15}?确认收款")

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
    private val ACCOUNT_LABELS = listOf(
        "付款方式", "支付方式", "付款账户", "支付账户", "扣款方式", "付款渠道",
        // 支付宝支付成功页的付款方式标签
        "交易方式"
    )

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

    /** 收入侧兜底分类（支出侧兜底用 [CategoryCatalog.FALLBACK]） */
    private const val INCOME_FALLBACK = "其他收入"

    /** 红包通道的落点：支出红包属于人情往来（商户名是领取人，平台分类也拿不到） */
    private const val RED_PACKET_CATEGORY = "发红包"

    /** 分类表全部合法名称（一级 + 二级）：映射落点必须在这里面，分类名不允许游离在分类表外 */
    private val CATEGORY_NAMES: Set<String> = CategoryCatalog.allNames().toSet()

    /**
     * 平台分类 → 本 App 分类名（**支出侧**，二级优先、粗分类落一级）。
     *
     * 平台只给粗分类（支付宝「餐饮美食」/ 微信「购物」），能细分就落二级；
     * 粗到无法细分时落一级（「交通出行」→ 交通）—— 分类体系允许只记一级。
     * **顺序即优先级**：具体项（外卖 / 咖啡奶茶 / 电商）排在宽泛项（餐饮 / 购物）之前。
     */
    private val PLATFORM_CATEGORY_MAP = listOf(
        // 餐饮
        "外卖" to listOf("外卖"),
        "咖啡奶茶" to listOf("咖啡", "奶茶"),
        "正餐" to listOf("餐厅", "饭店", "正餐", "餐馆"),
        "餐饮" to listOf("餐饮", "美食", "食品"),
        // 购物
        "电商" to listOf("电商", "购物", "商城", "网购"),
        "超市便利" to listOf("超市", "便利店"),
        "家居日用" to listOf("日用百货", "日用", "家居", "居家", "百货"),
        // 交通 / 汽车
        "打车" to listOf("打车", "网约车"),
        "公交地铁" to listOf("公交", "地铁"),
        "火车高铁" to listOf("火车", "铁路", "高铁", "12306"),
        "飞机" to listOf("机票", "航班", "航空"),
        "加油" to listOf("加油"),
        "停车" to listOf("停车"),
        "汽车" to listOf("汽车", "养车"),
        "交通" to listOf("交通", "出行"),
        // 居住 / 通讯
        "水电燃气" to listOf("水电", "燃气"),
        "物业" to listOf("物业"),
        "居住" to listOf("居住", "生活缴费", "房租"),
        "话费" to listOf("话费", "通讯"),
        // 医疗健康
        "门诊" to listOf("门诊"),
        "医疗健康" to listOf("医疗", "健康", "医药", "就医"),
        // 娱乐休闲
        "电影" to listOf("电影"),
        "游戏" to listOf("游戏"),
        "会员订阅" to listOf("会员", "视频", "音乐"),
        "娱乐休闲" to listOf("休闲", "娱乐", "文化"),
        // 其他平台分类
        "保险" to listOf("保险"),
        "数码电子" to listOf("数码", "电器", "电子"),
        "服饰美妆" to listOf("服饰", "装扮", "美妆", "化妆", "美容"),
        "教育学习" to listOf("教育", "培训", "学习"),
        "旅游" to listOf("旅游", "度假", "酒店"),
        "住宿" to listOf("民宿", "住宿"),
        "发红包" to listOf("红包"),
        "其他" to listOf("其他")
    )

    /**
     * 商户关键词 → 二级分类名（**支出侧**）。
     *
     * **顺序即优先级**：具体商户在前、宽泛品类在后 ——
     * `美团单车` 必须先于 `美团` 命中，否则共享单车会被判成外卖；命中即返回，不再找更贴切的词。
     */
    private val MERCHANT_CATEGORY_KEYWORDS = listOf(
        // 交通：细分项先于「美团」这类会跨品类命中的平台名
        "共享单车" to listOf("美团单车", "哈啰", "青桔", "共享单车", "摩拜"),
        "火车高铁" to listOf("12306", "铁路", "火车", "高铁", "中铁"),
        "飞机" to listOf("航空公司", "机票", "航班"),
        "公交地铁" to listOf("地铁", "公交", "一卡通", "交通卡", "乘车码", "轨道交通"),
        "打车" to listOf("滴滴", "打车", "曹操", "t3", "花小猪", "首汽", "享道", "网约车"),
        // 汽车
        "车险" to listOf("车险", "交强险"),
        "加油" to listOf("中石化", "中石油", "中国石化", "中国石油", "加油", "壳牌", "中化石油", "油站"),
        "停车" to listOf("停车", "泊车", "停车场"),
        "过路费" to listOf("高速", "etc", "过路费", "通行费"),
        "违章罚款" to listOf("违章", "罚款", "交警"),
        "洗车" to listOf("洗车"),
        "保养维修" to listOf("保养", "汽修", "维修", "4s"),
        "车贷" to listOf("车贷"),
        // 餐饮
        "外卖" to listOf("美团外卖", "饿了么", "外卖", "美团"),
        "咖啡奶茶" to listOf(
            "星巴克", "瑞幸", "咖啡", "奶茶", "喜茶", "奈雪", "蜜雪冰城", "霸王茶姬", "茶颜悦色"
        ),
        "正餐" to listOf(
            "麦当劳", "肯德基", "汉堡王", "必胜客", "餐厅", "饭店", "火锅", "烧烤",
            "湘菜", "川菜", "餐馆", "食府", "面馆", "食堂", "小吃"
        ),
        "早餐" to listOf("早餐"),
        "零食" to listOf("零食", "良品铺子", "三只松鼠", "来伊份"),
        "水果" to listOf("水果", "百果园", "鲜丰水果"),
        "烟酒" to listOf("烟酒", "茅台", "五粮液"),
        // 购物
        "电商" to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "抖音商城", "苏宁", "闲鱼", "得物"
        ),
        "超市便利" to listOf(
            "超市", "便利店", "盒马", "沃尔玛", "永辉", "家乐福", "美宜佳", "罗森",
            "全家", "7-11", "便利蜂", "大润发", "华润万家"
        ),
        "家居日用" to listOf("日用", "百货", "名创优品", "宜家", "无印良品", "家居"),
        "其他购物" to listOf("购物中心", "商场", "奥特莱斯", "免税店"),
        // 居住 / 通讯
        "水电燃气" to listOf(
            "水费", "电费", "燃气", "国家电网", "供电", "自来水", "供水", "天然气", "水务", "电力"
        ),
        "物业" to listOf("物业"),
        "房租" to listOf("房租", "租金", "租房"),
        "房贷" to listOf("房贷", "按揭"),
        "宽带" to listOf("宽带", "光纤"),
        "家政" to listOf("家政", "保洁", "保姆"),
        "装修" to listOf("装修", "建材", "家装"),
        "话费" to listOf(
            "话费", "手机充值", "中国移动", "中国联通", "中国电信", "联通", "移动", "电信"
        ),
        "流量" to listOf("流量"),
        // 医疗健康
        "门诊" to listOf("医院", "诊所", "门诊", "挂号", "卫生院", "卫生服务中心"),
        "药品" to listOf("药房", "药店", "大药房", "医药", "药品"),
        "体检" to listOf("体检", "健康检查"),
        "牙科" to listOf("牙科", "口腔", "齿科"),
        "住院" to listOf("住院"),
        "保健品" to listOf("保健"),
        // 娱乐休闲
        "电影" to listOf("电影", "影院", "影城", "猫眼", "淘票票"),
        "游戏" to listOf("游戏", "steam", "王者荣耀", "和平精英", "米哈游", "原神", "点卡"),
        "会员订阅" to listOf(
            "会员", "视频", "音乐", "爱奇艺", "优酷", "芒果tv", "网易云", "qq音乐",
            "酷狗", "喜马拉雅", "哔哩哔哩", "b站", "netflix", "spotify"
        ),
        "演出" to listOf("演出", "演唱会", "话剧", "音乐剧", "大麦"),
        "KTV" to listOf("ktv"),
        "酒吧" to listOf("酒吧", "酒馆"),
        // 旅游
        "住宿" to listOf(
            "酒店", "民宿", "宾馆", "客栈", "华住", "如家", "汉庭", "万豪", "希尔顿",
            "亚朵", "锦江", "全季"
        ),
        "旅游" to listOf(
            "旅游", "旅行", "度假", "携程", "飞猪", "去哪儿", "同程", "途牛", "马蜂窝"
        ),
        "景点门票" to listOf("景区", "门票", "动物园", "博物馆"),
        "签证" to listOf("签证"),
        // 教育学习
        "课程培训" to listOf("教育", "培训", "学费", "网课", "课程", "学而思", "新东方"),
        "书籍" to listOf("书店", "图书", "当当", "新华书店", "购书"),
        "考试报名" to listOf("考试", "报名费", "考证", "托福", "雅思"),
        "文具" to listOf("文具", "晨光"),
        // 人情往来
        "发红包" to listOf("红包"),
        "礼物" to listOf("礼物", "礼品", "花店", "鲜花"),
        "捐赠" to listOf("捐赠", "公益", "慈善", "爱心"),
        // 金融保险
        "保险" to listOf("保险", "人寿", "众安"),
        "手续费" to listOf("手续费", "服务费"),
        "利息" to listOf("利息"),
        "投资" to listOf("基金", "股票", "证券", "理财", "黄金"),
        "还款" to listOf("还款", "花呗", "借呗", "白条", "信用卡还款", "分期"),
        // 服饰美妆
        "衣服" to listOf("服饰", "服装", "优衣库", "男装", "女装", "内衣"),
        "鞋包" to listOf(
            "鞋", "箱包", "皮具", "耐克", "阿迪达斯", "李宁", "安踏", "斯凯奇", "新百伦"
        ),
        "化妆品" to listOf(
            "化妆品", "屈臣氏", "丝芙兰", "完美日记", "花西子", "欧莱雅", "雅诗兰黛",
            "资生堂", "美妆"
        ),
        "美容美发" to listOf("美容", "美发", "理发", "剪发", "沙龙"),
        "美甲" to listOf("美甲", "美睫"),
        // 母婴育儿
        "奶粉" to listOf("奶粉", "爱他美", "飞鹤", "美赞臣", "惠氏"),
        "尿布" to listOf("尿布", "纸尿裤", "帮宝适", "好奇", "尤妮佳"),
        "玩具" to listOf("玩具", "乐高", "泡泡玛特"),
        "早教" to listOf("早教", "亲子"),
        "儿童衣物" to listOf("童装", "儿童服饰"),
        // 宠物
        "宠物食品" to listOf("狗粮", "猫粮", "宠粮", "宠物食品"),
        "宠物用品" to listOf("宠物用品", "宠物店"),
        "宠物医疗" to listOf("宠物医院", "宠物医疗", "兽医"),
        "宠物美容" to listOf("宠物美容"),
        "寄养" to listOf("寄养"),
        // 数码电子
        "手机" to listOf(
            "手机", "苹果", "iphone", "小米", "华为", "荣耀", "vivo", "oppo", "一加", "三星"
        ),
        "电脑" to listOf("电脑", "笔记本", "macbook", "联想", "戴尔", "惠普", "华硕"),
        "配件" to listOf("配件", "充电器", "耳机", "数据线", "键鼠", "移动电源"),
        "软件" to listOf("软件", "app store", "应用商店"),
        "云服务" to listOf("阿里云", "腾讯云", "华为云", "云服务", "服务器", "域名", "云主机"),
        // 运动健身
        "健身房" to listOf("健身", "瑜伽", "游泳馆", "私教"),
        "运动装备" to listOf("迪卡侬", "lululemon", "运动装备", "骑行", "球拍"),
        "场地费" to listOf("球馆", "网球场", "球场", "羽毛球", "场地"),
        // 生活服务（其他）
        "生活服务" to listOf(
            "快递", "顺丰", "中通", "圆通", "韵达", "申通", "邮政", "打印", "洗衣", "干洗", "洗护"
        )
    )

    /**
     * 平台分类 → 收入侧分类名。
     *
     * 收入分类大多是一级（工资 / 退款 / 报销），平台能细分的（基金 / 股票）按二级落。
     * 顺序同支出侧：具体在前，`其他收入` 兜底在最后。
     */
    private val INCOME_PLATFORM_CATEGORY_MAP = listOf(
        "工资" to listOf("工资", "薪资", "薪酬"),
        "奖金" to listOf("奖金", "年终奖"),
        "兼职" to listOf("兼职", "稿费"),
        "理财收益" to listOf("理财", "基金", "股票", "利息", "分红"),
        "红包" to listOf("红包", "礼金"),
        "退款" to listOf("退款", "退货"),
        "报销" to listOf("报销"),
        "其他收入" to listOf("其他收入", "收入", "收款", "转账")
    )

    /** 商户关键词 → 收入侧分类名；退款 / 报销先于「到账」这类宽泛词，避免退款到账被归成其他收入 */
    private val INCOME_MERCHANT_CATEGORY_KEYWORDS = listOf(
        "退款" to listOf("退款", "退货", "退票", "返款", "撤销"),
        "报销" to listOf("报销", "差旅"),
        "理财收益" to listOf(
            "理财收益", "基金", "股票", "分红", "利息", "收益", "余额宝", "零钱通"
        ),
        "工资" to listOf("工资", "薪资", "薪酬", "代发"),
        "奖金" to listOf("奖金", "年终奖", "绩效", "提成", "项目奖"),
        "兼职" to listOf("兼职", "稿费", "外快", "劳务"),
        "红包" to listOf("红包", "礼金", "压岁钱"),
        "其他收入" to listOf("收款", "转入", "转账", "到账")
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
    fun parseSentRedPacketChat(
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
        if (!isSentRedPacketChatPage(lines, allowBubbleOnly)) return null

        // 只有气泡、没有「已领取」强信号时，再用气泡金额核对一次：
        // 聊天记录里旧红包金额对不上，就不能当成本笔（宁可漏记也不能记错）。
        val joined = lines.joinToString(" ")
        val hasClaimSignal = RED_PACKET_SENT_WORDS.any { joined.contains(it) }
        val bubbleAmount = extractRedPacketBubbleAmount(lines)
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
            ?: extractRedPacketClaimer(lines).ifBlank { extractChatContact(lines) }
        val type = AutoBookDraft.TYPE_EXPENSE
        return AutoBookDraft(
            channel = rule.channel,
            type = type,
            amount = amount,
            merchant = merchant,
            // 发红包是固定语义：商户名是领取人（张三），平台分类也拿不到，走关键词只会落到「其他」
            categoryName = RED_PACKET_CATEGORY,
            accountName = rule.accountName,
            payMethod = pre?.payMethod.orEmpty(),
            productDesc = "",
            orderId = "",
            platformCategory = "",
            timeText = "",
            sourceId = buildSourceId(rule.channel, type, amount, merchant, "", "", nowMs),
            dedupKeys = buildDedupKeys(rule.channel, type, amount, "", ""),
            markers = listOf("红包已发出")
        )
    }

    /**
     * 红包付款前页面（输入页 / 塞钱页 / 确认页）—— 用于给 [parseSentRedPacketChat] 打「这笔是红包」标记。
     *
     * 为什么要单独认：支付宝红包付款成功后没有结果页，聊天页里本来就有历史红包气泡；
     * 只有「刚刚走过红包付款流程」这一事实，才能把新气泡和旧气泡区分开。
     */
    fun isRedPacketPrePayPage(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        val joined = lines.joinToString(" ")
        if (!joined.contains("红包")) return false
        // 聊天页底部也有「发红包」快捷入口，必须排除会话页
        if (CHAT_PAGE_WORDS.any { joined.contains(it) }) return false
        if (RED_PACKET_ENTRY_WORDS.none { joined.contains(it) }) return false
        // 收银台把金额写在「付款金额0.01元人民币」里，而这一行同时含「红包」——
        // extractAmount 会按金额噪声跳过它，所以这里用红包专用正则再兜一次。
        return extractAmount(lines) != null || RED_PACKET_AMOUNT_TEXT.containsMatchIn(joined)
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
    private fun isSentRedPacketChatPage(lines: List<String>, allowBubbleOnly: Boolean): Boolean {
        val joined = lines.joinToString(" ")
        if (!joined.contains("红包")) return false
        if (!CHAT_PAGE_WORDS.any { joined.contains(it) }) return false
        if (PRE_PAY_WORDS.any { joined.contains(it) }) return false
        if (RED_PACKET_SENT_WORDS.any { joined.contains(it) }) return true
        // 对方还没领取：只有 `支付宝红包` 气泡。必须已经「验过指纹/密码」才算付款成功，
        // 避免在发红包页取消后打开旧聊天被历史红包触发。
        return allowBubbleOnly && joined.contains("支付宝红包")
    }

    /** 红包付款流程里是否已经到了指纹 / 密码验证页（离付款成功只差一步）。 */
    fun isRedPacketFingerprintPage(lines: List<String>): Boolean =
        lines.any { line ->
            RED_PACKET_FINGERPRINT_WORDS.any { line.contains(it) }
        }

    /**
     * 从红包气泡邻域取金额：`支付宝红包 / 清风明月 / ¥0.01 / 示例留言` → 0.01。
     *
     * 不能对整页 [extractAmount]：聊天记录里可能还有别的转账/红包气泡金额，页面上先出现的未必是这一笔。
     * 只取「支付宝红包」标签后的 5 行，命中带货币符号 / 两位小数的金额。
     */
    private fun extractRedPacketBubbleAmount(lines: List<String>): Double? {
        val index = lines.indexOfFirst { it.contains("支付宝红包") }
        if (index < 0) return null
        val end = minOf(lines.size, index + 5)
        for (i in index until end) {
            val line = lines[i]
            if (isAmountNoise(line)) continue
            val match = RED_PACKET_BUBBLE_AMOUNT.find(line) ?: continue
            toAmount(match.groupValues[1])?.let { return it }
        }
        return null
    }

    /** 聊天页顶部联系人名（`返回 资金往来记录 聊天设置` 的下一行，如 `张三`） */
    private fun extractChatContact(lines: List<String>): String {
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
    private fun extractRedPacketClaimer(lines: List<String>): String {
        for (line in lines) {
            val m = RED_PACKET_CLAIMED_BY.find(line) ?: continue
            val name = m.groupValues[1].trim().trimStart('，', ',', '、', ' ')
            if (name.isNotEmpty() && !name.contains("你") && !name.contains("红包")) return name.take(20)
        }
        return ""
    }

    // ==================== 账单页门禁 ====================

    /**
     * **账单页白名单的强字段标签**。
     *
     * 账单 / 交易详情页才有的字段：支付时间 / 订单号 / 商品说明 / 商户全称 / 账单分类 / 交易状态。
     * 门禁要求其中 **≥2 个**取到值才认（[isStrongBillDetailPage]），单个字段不算 ——
     * 聊天 / 通知里也会出现「收款方…」这样的句子（真机 2026-10-17 微信首页会话列表）。
     *
     * ⚠️ **不要放「交易详情 / 付款详情 / 转账详情」**：真机实测微信支付**会话页**的支付消息卡片，
     * 会把按钮文字拼进整张卡片的 content-desc —— `使用零钱支付￥43.00交易详情`，
     * 于是「交易详情」被当成字段标签、又顺手取到了下一行的值，会话页就被误判成详情页、当场弹窗。
     * 它们是标题 / 按钮，不是「标签 → 值」字段。
     *
     * ⚠️ **也不要放「付款方式 / 交易方式」这类弱字段**：支付宝/微信的**支付成功结果页**也带
     * `交易方式 余额宝…`，用宽标签集会把列表页 / 结果页误判成详情页。
     */
    private val STRONG_DETAIL_LABELS: List<String> = TIME_LABELS + ORDER_LABELS + PRODUCT_LABELS +
        listOf("商户全称", "收款方全称", "账单分类", "交易状态", "当前状态")

    /**
     * 列表 / 摘要页特征词。
     *
     * 真机实测支付宝首页：「最近消息」「还有3条未读服务消息」+ 下拉刷新的「松开刷新」提示，
     * 这些词一出现就说明当前是消息流，而不是账单详情页。
     */
    private val LIST_PAGE_WORDS = listOf(
        "最近消息", "未读服务消息", "松开刷新", "下拉刷新", "上拉加载", "加载更多"
    )

    /**
     * 通知 / 会话预览文案。
     *
     * ⚠️ 真机事故（2026-10-17 20:44~20:47，微信首页会话列表）：会话预览
     * `收款方24小时内未接收你的转账，已过期` 被 `收款方` 标签当成「标签 → 值」，
     * 首页于是被判成账单详情页、当场弹浮窗。这类系统通知不是账单字段，不能当值用。
     */
    private val NOTIFICATION_TEXT = Regex("(未接收|已过期|请查收|请尽快|超时未|待领取|已领取|领取中)")

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

    /**
     * 支付宝红包**付款前**页面的入口特征词（比 [RED_PACKET_PRE_PAY_WORDS] 宽）。
     *
     * 真机 2026-10-17 16:38：确认页按钮是「发红包」、页脚是「24小时内未被领取，红包金额将退回」，
     * 收银台写的是「发普通红包」（不是「发红包」）—— 只认「发红包」会在收银台丢掉红包标记。
     */
    private val RED_PACKET_ENTRY_WORDS = listOf(
        "塞钱进红包", "未领取的红包", "发红包", "发普通红包",
        "红包封面", "未被领取", "红包金额", "红包记录"
    )

    /** 红包付款前的验证页：到这一步说明用户已经确认付款，聊天页气泡出现的概率极高 */
    private val RED_PACKET_FINGERPRINT_WORDS = listOf(
        "请验证指纹", "正在校验指纹", "验证指纹", "请输入支付密码", "输入支付密码"
    )

    /** 红包付款前页面上的金额（收银台：`付款金额0.01元人民币`；确认页：`金额 0.01 元`） */
    private val RED_PACKET_AMOUNT_TEXT = Regex("(?:付款金额|红包金额|金额)[^0-9]{0,6}([0-9]+(?:\\.[0-9]{1,2})?)")

    /**
     * 支付宝红包**发出后**聊天页上的「已发出 / 已被领取」信号。
     *
     * 真机实测（2026-10-17 16:28 / 16:45，支付宝）：发红包付款成功后**没有**任何结果页 ——
     * 直接回到聊天页。气泡文案有两种：
     * - 对方已领取：`红包已被领取`、`icon 张三领取了你的 红包`；
     * - 对方未领取：`支付宝红包 16:45` + `¥0.01` + 留言。
     * 金额优先取付款前页面预采集到的 [PrePaymentDraft]，缺失时用气泡金额兜底。
     */
    private val RED_PACKET_SENT_WORDS = listOf("红包已被领取", "领取了你的")

    /**
     * 聊天 / 会话页特征词：用来把「红包气泡所在的聊天页」和「发红包输入页」区分开。
     *
     * ⚠️ 不能用「发红包」—— 聊天页底部的快捷入口也叫「发红包」，用了会把聊天页当成付款前页面。
     */
    private val CHAT_PAGE_WORDS = listOf("发送消息", "资金往来记录", "聊天设置")

    /** 从 `icon 张三领取了你的 红包` 里取领取人（= 收红包的人，可当商户名） */
    private val RED_PACKET_CLAIMED_BY = Regex("(?:icon\\s*)?([^\\s|]{1,20}?)\\s*领取了你的\\s*红包")

    /** 红包气泡上的金额（`¥0.01` / `0.01`），只在气泡邻域里找 */
    private val RED_PACKET_BUBBLE_AMOUNT = Regex("[¥￥]\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)")

    /**
     * 付款前页面的特征词：收银台 / 指纹验证 / 密码输入。
     *
     * 真机实测（2026-10-17，微信转账）：这两页同样带「付款方式 + 零钱 + 金额」，
     * 旧门禁把它们当成账单页 —— 用户还在指纹页时就识别了一次、付款前又识别一次。
     * 只要出现这些词，字段再齐全也不是结果页。
     */
    private val PRE_PAY_WORDS = listOf(
        "请验证指纹", "验证指纹", "使用密码", "输入支付密码", "面容验证", "识别面容",
        "立即支付", "确认付款", "确认支付",
        // 支付宝转账输入页 / 添加备注弹窗（真机 2026-10-17）：这些页同样有金额与「付款」，
        // 不挡掉会在用户付款前弹窗（甚至把天气「多云 27℃」当商户）
        "付款给个人", "输入付款金额", "推荐付款方式", "添加备注", "备注一下", "收款人可见"
    )

    /**
     * 预采集识别的词：比 [PRE_PAY_WORDS] 宽 —— 转账**输入页**没有密码/指纹，
     * 但同样属于「付款前」，而且收款方与用户备注恰恰只在这里出现。
     *
     * 只用于 [parsePrePayment]（调用方在结果页解析失败后才走到它），不会放宽账单门禁。
     */
    private val PRE_PAY_PAGE_WORDS = PRE_PAY_WORDS + listOf(
        "付款给", "添加备注", "转账说明", "转账金额", "付款金额", "输入金额", "确认转账"
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
     * 一屏里出现 ≥ [MULTI_TRANSACTION_THRESHOLD] 处多笔交易的痕迹。
     *
     * 真机实测支付宝「支付消息」列表：每张卡片都自带一份「付款方式」，也各自有「付款成功」——
     * 只看「有没有详情标签」会把它误判成账单详情页，而且列表第一条的金额商户**恰好也能提取出来**，
     * 于是弹出一个「看起来是对的」的浮窗。所以多笔信号必须**优先于**详情结构判定。
     */
    private fun hasMultipleTransactions(lines: List<String>): Boolean {
        // ⚠️ 不再数「支付成功」出现次数：支付宝结果页标题与正文各有一处
        // （`支付成功￥0.01 回首页` + `支付成功`），旧实现把这个单笔页误杀成「非账单页」
        // （真机 2026-10-17：支付宝付完不弹窗）。
        val labelHits = lines.count { line -> ACCOUNT_LABELS.any { line.contains(it) } }
        if (labelHits >= MULTI_TRANSACTION_THRESHOLD) return true
        // 单笔页一屏只有一个**不同的**金额；出现两个不同金额才是流水 / 列表。
        // 结果页的标题 / 正文 / 商户行会重复同一个金额，按「行数」数会误伤。
        return countDistinctAmounts(lines) >= MULTI_TRANSACTION_THRESHOLD
    }

    /**
     * 一屏里**不同金额值**的个数。
     *
     * 支付宝结果页 `支付成功￥0.01 回首页 | 0.01 | 楠楠… ￥0.01` 三行都是同一笔 0.01；
     * 支付消息列表则是 3.00 / 81.00 / 849.53 三个不同值。
     */
    private fun countDistinctAmounts(lines: List<String>): Int =
        lines.asSequence()
            .mapNotNull { line -> extractAmount(listOf(line))?.second }
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
    fun isBillPage(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        // 付款前页面（收银台 / 指纹 / 密码）：不是结果页，页面上的金额还没付出去
        if (lines.any { line -> PRE_PAY_WORDS.any { line.contains(it) } }) return false
        // 一屏多笔 = 列表 / 流水：单笔账单页只描述一笔交易
        if (hasMultipleTransactions(lines)) return false
        // 白名单：命中任一账单页签名才识别
        if (isStandaloneResultPage(lines)) return true
        if (isStrongBillDetailPage(lines)) return true
        return isRedPacketResultPage(lines)
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
    private fun isStrongBillDetailPage(lines: List<String>): Boolean {
        if (extractAmount(lines) == null) return false
        val hits = STRONG_DETAIL_LABELS.count { label -> labelValue(lines, listOf(label)) != null }
        if (hits < 1) return false
        return hasDetailPageTitle(lines)
    }

    /**
     * 页面标题是否属于详情页名单。
     *
     * 允许标题带前缀（真机微信导航栏与菜单项会被合成一行 `服务 会员 交易详情`），
     * 但长度只放宽 8 个字符 —— 聊天列表的长句（`收款方24小时内未接收你的转账，已过期`）不会命中。
     */
    private fun hasDetailPageTitle(lines: List<String>): Boolean = lines.any { raw ->
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
    fun isDetailPage(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        if (lines.any { line -> PRE_PAY_WORDS.any { line.contains(it) } }) return false
        if (hasMultipleTransactions(lines)) return false
        if (isListOrSummaryPage(lines)) return false
        if (FUTURE_WORDS.any { word -> lines.any { it.contains(word) } }) return false
        return STRONG_DETAIL_LABELS.any { label -> labelValue(lines, listOf(label)) != null }
    }

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
        // 红包结果页是**极简页**（真机只有 4 行：`返回 | 微信红包 | ￥0.01 | 微信支付`）。
        // 会话列表 / 聊天页动辄 10+ 行，靠行数先挡一道。
        if (lines.size > RED_PACKET_RESULT_MAX_LINES) return false
        val joined = lines.joinToString(" ")
        // 必须是产品名「微信红包」整词 —— 会话预览「红包退款到账通知」里的「红包」不算
        if (!joined.contains("微信红包")) return false
        if (!joined.contains("微信支付")) return false
        if (RED_PACKET_PRE_PAY_WORDS.any { joined.contains(it) }) return false
        // 红包结果页的金额带货币符号（`￥0.01`）；聊天 / 群消息里的「40元」不是红包金额
        return lines.any { SYMBOL_AMOUNT.containsMatchIn(stripClock(it)) }
    }

    /** 红包结果页最多这么多行（真机结果页 4 行；会话列表 / 聊天页远多于它） */
    private const val RED_PACKET_RESULT_MAX_LINES = 8

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
            val m = PREFIXED_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[2])?.let { return raw to it }
        }
        // 2) 带货币符号
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            val m = SYMBOL_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[1])?.let { return raw to it }
        }
        // 3) 带「元」
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            val m = YUAN_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[1])?.let { return raw to it }
        }
        // 4) 裸两位小数（支付宝/微信详情页大字金额，如 -3.00）
        for ((raw, line) in pairs) {
            if (isAmountNoise(line)) continue
            if (line.contains('元') || line.contains('¥') || line.contains('￥')) continue
            val m = PLAIN_AMOUNT.find(line) ?: continue
            toAmount(m.groupValues[2])?.let { return raw to it }
        }
        return null
    }

    /** 抹掉时间戳（`20:47` / `23:14:43`），金额匹配只在抹除后的文本上进行 */
    private fun stripClock(line: String): String = CLOCK.replace(line, " ")

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
     * 3. 判定前清洗品牌名与方向标签，避免「支付宝」的「支付」二字 / 「收款方」的「收款」二字把方向判反；
     * 4. 都没命中时按支出处理：宁可让用户改一次类型，也不要漏记一笔支出。
     */
    fun detectType(lines: List<String>, amountLine: String): Int {
        val amountLineClean = neutralizeDirectionWords(cleanBrand(amountLine))
        if (INCOME_WORDS.any { amountLineClean.contains(it) }) return AutoBookDraft.TYPE_INCOME
        if (EXPENSE_WORDS.any { amountLineClean.contains(it) }) return AutoBookDraft.TYPE_EXPENSE

        val joined = neutralizeDirectionWords(cleanBrand(lines.joinToString(" ")))
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

    /** 抹掉只表达「谁收谁付」的标签词，保留真正的方向语义（见 [DIRECTION_LABEL_NOISE]）。 */
    private fun neutralizeDirectionWords(text: String): String {
        var result = text
        for (label in DIRECTION_LABEL_NOISE) {
            result = result.replace(label, "▢")
        }
        return PENDING_RECEIPT.replace(result, "▢")
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
        // 兜底：值不能是纯数字（「推荐付款方式」后面紧跟的金额行）
        if (method.toDoubleOrNull() != null) return null
        return method.take(20)
    }

    /**
     * 是否是付款前页面（收银台 / 指纹 / 密码输入 / 转账确认）。
     *
     * 注意：它**不是**账单页 —— [isBillPage] 会拒绝它；这里只用于预采集。
     */
    fun isPrePaymentPage(lines: List<String>): Boolean =
        extractAmount(lines) != null &&
            lines.any { line -> PRE_PAY_PAGE_WORDS.any { line.contains(it) } }

    /**
     * 预采集付款前页面：金额 / 收款方 / 付款方式 / 用户备注。
     *
     * 这些字段在支付结果页往往缺失（微信转账成功页尤其极简），只能趁用户还在付款页时抓到。
     * 返回 null 表示「不是付款前页面」或「金额都取不到」，调用方不应缓存。
     */
    fun parsePrePayment(rule: AutoBookRule, lines: List<String>, nowMs: Long): PrePaymentDraft? {
        if (!isPrePaymentPage(lines)) return null
        val amount = extractAmount(lines) ?: return null
        return PrePaymentDraft(
            channel = rule.channel,
            type = detectType(lines, amount.first),
            amount = amount.second,
            merchant = extractPrePaymentMerchant(lines),
            payMethod = extractAccount(lines).orEmpty(),
            remark = extractPrePaymentRemark(lines),
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
    private fun extractPrePaymentMerchant(lines: List<String>): String {
        for ((index, line) in lines.withIndex()) {
            val at = line.indexOf("付款给")
            if (at < 0) continue
            // 尾部可能粘着按钮文字（真机：`付款给张三(***) 返回`）—— 收款方不含空格，取第一个词
            val tail = stripZeroWidth(line.substring(at + 3)).substringBefore(" ").trim()
            if (tail.isNotEmpty() && !tail.startsWith("个人")) return tail.take(40)
            val next = lines.getOrNull(index + 1)?.let { stripZeroWidth(it) }
            if (!next.isNullOrEmpty() && !isLabelLike(next)) return next.take(40)
        }
        val labeled = labelValue(lines, listOf("收款方", "收款方全称", "收款人", "商户名称", "商户全称"))
        if (!labeled.isNullOrBlank()) return labeled.take(40)
        return ""
    }

    /**
     * 付款前页面上用户填的备注。
     *
     * 真机原文形如 `备注 修⁠改`（含零宽字符）或 `备注 午饭`；「修改 / 添加备注」是入口文案，
     * 不能当成备注内容。
     */
    private fun extractPrePaymentRemark(lines: List<String>): String {
        for (line in lines) {
            val at = line.indexOf("备注")
            if (at < 0) continue
            val tail = stripZeroWidth(line.substring(at + 2)).trimStart('：', ':').trim()
            // 只认同一行的值：真机的「备注」行后面会跟「付款」按钮 / 数字键盘 /「修改」入口，
            // 取下一行会得到 `付款`、`1 2 3 删除` 这种脏值（2026-10-17 真机日志）。
            if (tail.isEmpty() || tail in REMARK_PLACEHOLDERS || looksLikeUiText(tail)) continue
            return tail.take(40)
        }
        return ""
    }

    /** 备注行里混进的 UI 文案 / 键盘行（不是用户输入）。 */
    private fun looksLikeUiText(text: String): Boolean =
        text.any { it.isDigit() } ||
            listOf(
                "付款", "取消", "确定", "删除", "支付", "返回",
                "修改", "添加备注", "备注一下", "收款人可见"
            ).any { text.contains(it) }

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
    private fun stripZeroWidth(text: String): String =
        text.replace(Regex("[\u200B-\u200D\u2060\uFEFF]"), "").trim()

    /** 备注行的占位文案（不是用户填的内容）。 */
    private val REMARK_PLACEHOLDERS = setOf("修改", "添加备注", "添加", "编辑", "填写备注", "备注一下", "无")

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
    private fun isNotificationText(text: String): Boolean = NOTIFICATION_TEXT.containsMatchIn(text)

    /** 值只是一个时间戳（`20:36`）：会话列表的列表项时间，不是字段值 */
    private fun isClockOnly(text: String): Boolean = CLOCK.matches(text.trim())

    /** 是否是汉字（判断标签后面是否紧跟了更长词的片段；全角标点不算） */
    private fun isCjkIdeograph(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private fun isLabelLike(text: String): Boolean {
        val labels = MERCHANT_LABELS + ACCOUNT_LABELS + TIME_LABELS + CATEGORY_LABELS + ORDER_LABELS +
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

    private fun isUsableMerchant(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..20) return false
        // 列表项时间（`20:36`）不是商户名（真机 2026-10-17 微信首页会话列表）
        if (CLOCK.matches(trimmed)) return false
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
    fun mapCategory(platformCategory: String?, merchant: String, type: Int): String {
        val isIncome = type == AutoBookDraft.TYPE_INCOME
        if (!platformCategory.isNullOrBlank()) {
            val platformMap = if (isIncome) INCOME_PLATFORM_CATEGORY_MAP else PLATFORM_CATEGORY_MAP
            for ((target, keywords) in platformMap) {
                if (keywords.any { platformCategory.contains(it) }) return categoryOrFallback(target, isIncome)
            }
        }
        if (merchant.isNotBlank()) {
            val lower = merchant.lowercase()
            val merchantMap = if (isIncome) INCOME_MERCHANT_CATEGORY_KEYWORDS else MERCHANT_CATEGORY_KEYWORDS
            for ((target, keywords) in merchantMap) {
                if (keywords.any { lower.contains(it) }) return categoryOrFallback(target, isIncome)
            }
        }
        return fallbackCategory(isIncome)
    }

    /** 映射目标只允许落在分类表内（一级 + 二级），否则回退兜底一级 */
    private fun categoryOrFallback(target: String, isIncome: Boolean): String =
        if (target in CATEGORY_NAMES) target else fallbackCategory(isIncome)

    private fun fallbackCategory(isIncome: Boolean): String =
        if (isIncome) INCOME_FALLBACK else CategoryCatalog.FALLBACK

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
