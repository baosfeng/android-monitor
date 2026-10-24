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

    fun parse(rule: AutoBookRule, lines: List<String>, nowMs: Long): AutoBookDraft? =
        AutoBookParseFlow.parseInternal(this, rule, lines, nowMs, requireBillPage = true)

    /**
     * 解析通知文本（**通知入口**，供后续接入 NotificationListenerService 使用）。
     *
     * 通知由系统直接投递、来源明确，不存在「首页消息摘要」这类混淆，因此不做页面门禁。
     */
    fun parseNotification(rule: AutoBookRule, lines: List<String>, nowMs: Long): AutoBookDraft? =
        AutoBookParseFlow.parseInternal(this, rule, lines, nowMs, requireBillPage = false)

    // ==================== 红包识别（实现见 AutoBookRedPacket） ====================

    /** 从聊天页识别「已发出的红包」。 */
    fun parseSentRedPacketChat(
        rule: AutoBookRule, lines: List<String>, pre: PrePaymentDraft?, nowMs: Long,
        redPacketFlowPending: Boolean = false, allowBubbleOnly: Boolean = false,
    ): AutoBookDraft? = AutoBookRedPacket.parseSentRedPacketChat(
        this, rule, lines, pre, nowMs, redPacketFlowPending, allowBubbleOnly,
    )

    /** 红包付款前页面。 */
    fun isRedPacketPrePayPage(lines: List<String>): Boolean =
        AutoBookRedPacket.isRedPacketPrePayPage(this, lines)

    /** 红包已验证指纹/密码的页面。 */
    fun isRedPacketFingerprintPage(lines: List<String>): Boolean =
        AutoBookRedPacket.isRedPacketFingerprintPage(this, lines)

    // ==================== 账单页门禁（实现见 AutoBookGate） ====================

    /** 这一屏是否像账单 / 交易详情页（门禁：强字段 ≥2）。 */
    fun isBillPage(lines: List<String>): Boolean = AutoBookGate.isBillPage(this, lines)

    /** 是否是交易详情页（已记账的笔走「补全」而不是弹窗）。 */
    fun isDetailPage(lines: List<String>): Boolean = AutoBookGate.isDetailPage(this, lines)

    // ==================== 纯文本工具（转发到字段提取） ====================

    internal fun stripClock(line: String): String = AutoBookFieldExtract.stripClock(line)

    internal fun stripZeroWidth(text: String): String = AutoBookFieldParse.stripZeroWidth(text)

    internal fun isLabelLike(text: String): Boolean = AutoBookFieldParse.isLabelLike(text)

    internal fun isAmountNoise(line: String): Boolean = AutoBookFieldExtract.isAmountNoise(line)

    internal fun toAmount(raw: String): Double? = AutoBookFieldExtract.toAmount(raw)

    // ==================== 付款前页面预采集（实现见 AutoBookPrePayment） ====================

    /** 是否是付款前页面（收银台 / 指纹 / 密码输入 / 转账确认）。它**不是**账单页。 */
    fun isPrePaymentPage(lines: List<String>): Boolean =
        AutoBookPrePayment.isPrePaymentPage(this, lines)

    /**
     * 预采集付款前页面：金额 / 收款方 / 付款方式 / 用户备注。
     *
     * 这些字段在支付结果页往往缺失（微信转账成功页尤其极简），只能趁用户还在付款页时抓到。
     * 返回 null 表示「不是付款前页面」或「金额都取不到」，调用方不应缓存。
     */
    fun parsePrePayment(rule: AutoBookRule, lines: List<String>, nowMs: Long): PrePaymentDraft? =
        AutoBookPrePayment.parsePrePayment(this, rule, lines, nowMs)

    /** 微信「添加备注」弹窗里的备注（只在弹窗出现时可见）。 */
    fun parseRemarkDialog(lines: List<String>): String? = AutoBookFieldParse.parseRemarkDialog(lines)

    // ==================== 字段提取（实现见 AutoBookFieldExtract / AutoBookFieldParse） ====================

    /** 提取金额：返回「金额原文行 → 数值」；取不到返回 null。 */
    fun extractAmount(lines: List<String>): Pair<String, Double>? = AutoBookFieldExtract.extractAmount(lines)

    /** 方向判定：支出 / 收入 / 转账。 */
    fun detectType(lines: List<String>, amountLine: String): Int =
        AutoBookFieldExtract.detectType(this, lines, amountLine)

    /** 商户名：先按标签取，再退到页面顶部标题。 */
    fun extractMerchant(lines: List<String>): String = AutoBookFieldExtract.extractMerchant(this, lines)

    /** 商品说明（支付宝账单详情页才有）。 */
    fun extractProductDesc(lines: List<String>): String =
        AutoBookFieldExtract.extractProductDesc(this, lines)

    /** 账户 / 付款方式。 */
    fun extractAccount(lines: List<String>): String? = AutoBookFieldExtract.extractAccount(this, lines)

    /** 交易时间。 */
    fun extractTime(lines: List<String>): String? = AutoBookFieldParse.extractTime(this, lines)

    /** 订单号。 */
    fun extractOrderId(lines: List<String>): String = AutoBookFieldParse.extractOrderId(this, lines)

    /** 按标签取值（同行右侧 / 下一行）。 */
    fun labelValue(lines: List<String>, labels: List<String>): String? =
        AutoBookFieldParse.labelValue(this, lines, labels)

    // ==================== 分类映射与幂等键（实现见 AutoBookIdentity） ====================

    /** 平台粗分类 / 商户关键词 → 本 App 分类名。 */
    fun mapCategory(platformCategory: String?, merchant: String, type: Int): String =
        AutoBookIdentity.mapCategory(this, platformCategory, merchant, type)

    /** 落库幂等键（source + sourceId）。 */
    fun buildSourceId(
        channel: String, type: Int, amount: Double, merchant: String,
        orderId: String, timeText: String, nowMs: Long,
    ): String = AutoBookIdentity.buildSourceId(this, channel, type, amount, merchant, orderId, timeText, nowMs)

    /** 弹窗去重键（精确指纹 + 模糊兜底）。 */
    fun buildDedupKeys(
        channel: String, type: Int, amount: Double, orderId: String, timeText: String,
    ): List<DedupKey> = AutoBookIdentity.buildDedupKeys(this, channel, type, amount, orderId, timeText)

    /** 交易时间归一成 `yyyy-MM-dd HH:mm:ss`；认不出返回 null。 */
    fun normalizeTradeTime(timeText: String): String? =
        AutoBookIdentity.normalizeTradeTime(this, timeText)

    fun md5(input: String): String = AutoBookIdentity.md5(this, input)
}
