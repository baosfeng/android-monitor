package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.Transaction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 详情页补全结果：[transaction] 为补全后的记录，[changed] 表示是否有字段真的发生变化。 */
internal data class DetailEnrichResult(
    val transaction: Transaction,
    val changed: Boolean
)

/**
 * 「已经记过账的那笔，用户后来打开交易详情页」时的匹配与补全（纯逻辑，可 JVM 单测）。
 *
 * 真机背景（2026-10-17 用户需求）：支付成功页只给金额、商户、付款方式，信息不够全；
 * 支付宝/微信的**交易详情页**才有订单号、精确时间、商品说明、商户全称。用户到详情页时：
 * - 如果这笔已经记过账 → **不弹记账浮窗**，把详情页字段补进已有记录，并弹 Toast「详情信息已补全」；
 * - 如果没记过账 → 保持原行为（正常弹记账浮窗）。
 *
 * 匹配规则（宁可配不上也不能改错账）：
 * - 只在**自动记账**记录里找（DAO 已按 `source = auto_*` 过滤）；
 * - 金额一致（±0.005）；
 * - 详情页有交易时间：优先 ±30 分钟内时间最近的一笔，其次同一天最近的一笔；
 * - 详情页没时间：最近 31 天内最新的一笔。
 *
 * 补全规则：**只补信息，不改用户的选择** —— 类型 / 分类 / 金额 / 账户 / 说明原样保留；
 * 商户名只在详情页给的更全（不更短）时替换；商品说明 / 付款方式 / 订单号 / 平台分类 / 交易时间
 * 各补各的结构化字段，**不再拼进账本「说明」**（2026-10-20 起说明只由用户在浮窗里写）。
 */
internal object AutoBookDetailEnricher {

    /**
     * 精确匹配窗口：详情页时间与已记账记录时间几乎一致（补全过详情后 dateTime 就是页面原文）。
     *
     * 用来在**同金额多笔**（真机 16:49 / 16:51 两个 0.01 红包）时避免把另一笔当成这一笔。
     */
    const val EXACT_WINDOW_MS = 60 * 1000L

    /**
     * 未补全记录的时间窗口：从结果页记账时，记录时间取的是「点记一笔」的时刻，
     * 与详情页的支付时间通常只差几十秒（自动记账是支付后立即弹窗）。
     *
     * 原来用 30 分钟，真机出现过把邻近同金额的另一笔配错；现在收紧到 2 分钟，
     * 且窗口内出现两笔接近的记录时**直接放弃**（宁可少补一次，也不改错账）。
     */
    const val NEAR_WINDOW_MS = 2 * 60 * 1000L

    /** 两笔记录的时间差在这个范围内视为「分不清」→ 不补全 */
    private const val AMBIGUITY_MS = 30 * 1000L

    /** 金额比较容差：两端都是页面上解析出来的两位小数，浮点误差远小于它 */
    private const val AMOUNT_EPS = 0.005

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 从候选记录里找这笔详情页对应的一条；找不到返回 null（调用方保持原来的弹窗行为）。 */
    fun findMatch(candidates: List<Transaction>, draft: AutoBookDraft, nowMs: Long): Transaction? {
        val pool = candidates.filter { abs(it.amount - draft.amount) < AMOUNT_EPS }
        if (pool.isEmpty()) return null

        // 详情页没给交易时间就**不猜**：金额相同不代表同一笔，宁可这次不补全。
        val normalized = AutoBookParser.normalizeTradeTime(draft.timeText) ?: return null
        val detailMs = parseMs(normalized) ?: return null

        // 1) 已补全过的记录：时间几乎一致就是它（重进同一详情页仍能认出来）
        val exact = pool.filter { !it.isUnenriched() }
            .mapNotNull { tx -> parseMs(tx.dateTime)?.let { ms -> tx to abs(ms - detailMs) } }
            .filter { it.second <= EXACT_WINDOW_MS }
            .minByOrNull { it.second }
            ?.first
        if (exact != null) return exact

        // 2) 未补全记录（记录时间 = 记账时刻，与支付时间差几十秒）：只在 ±2 分钟内认
        val near = pool.filter { it.isUnenriched() }
            .mapNotNull { tx -> parseMs(tx.dateTime)?.let { ms -> tx to abs(ms - detailMs) } }
            .filter { it.second <= NEAR_WINDOW_MS }
            .sortedBy { it.second }
        if (near.isEmpty()) return null

        // 3) 窗口内有第二笔同样接近 → 分不清，放弃（避免把同金额的另一笔改错）
        val bestGap = near.first().second
        if (near.count { it.second - bestGap <= AMBIGUITY_MS } > 1) return null
        return near.first().first
    }

    /** 是否还没补全过详情：订单号与页面交易时间都为空，说明它只见过结果页。 */
    private fun Transaction.isUnenriched(): Boolean =
        orderId.isBlank() && tradeTime.isBlank()

    /** 把详情页字段并进已有记录；没有任何变化时 [DetailEnrichResult.changed] 为 false。 */
    fun enrich(existing: Transaction, detail: AutoBookDraft, nowMs: Long): DetailEnrichResult {
        var changed = false

        // 商户：详情页给全称；已有名称更长时保留已有的（避免把「阿里云计算有限公司」覆盖成「阿里云」）
        var merchant = existing.merchant
        if (detail.merchant.isNotBlank() && detail.merchant != merchant &&
            (merchant.isBlank() || detail.merchant.length >= merchant.length)
        ) {
            merchant = detail.merchant
            changed = true
        }

        var productDesc = existing.productDesc
        if (detail.productDesc.isNotBlank() && detail.productDesc != productDesc) {
            productDesc = detail.productDesc
            changed = true
        }

        var payMethod = existing.payMethod
        if (detail.payMethod.isNotBlank() && detail.payMethod != payMethod) {
            payMethod = detail.payMethod
            changed = true
        }

        var orderId = existing.orderId
        if (detail.orderId.isNotBlank() && detail.orderId != orderId) {
            orderId = detail.orderId
            changed = true
        }

        var platformCategory = existing.platformCategory
        if (detail.platformCategory.isNotBlank() && detail.platformCategory != platformCategory) {
            platformCategory = detail.platformCategory
            changed = true
        }

        // 交易时间：详情页是最终口径，补上后同时修正 date / dateTime（对账与月度统计都靠它）
        var tradeTime = existing.tradeTime
        var date = existing.date
        var dateTime = existing.dateTime
        val normalized = AutoBookParser.normalizeTradeTime(detail.timeText)
        if (normalized != null && normalized != dateTime) {
            tradeTime = detail.timeText
            date = normalized.substringBefore(" ")
            dateTime = normalized
            changed = true
        }

        // 说明：**原样保留**。商品说明 / 付款方式各有独立字段，不再拼进账本说明 ——
        // 2026-10-20 起「说明」只由用户在浮窗里写，补全只改结构化字段。

        // 详情页有订单号 / 精确时间时，落库键也升级成更稳定的那一个，后续同一页不会再被当成新账
        var sourceId = existing.sourceId
        if ((orderId.isNotBlank() || normalized != null) && detail.sourceId != sourceId) {
            sourceId = detail.sourceId
            changed = true
        }

        if (!changed) return DetailEnrichResult(existing, false)
        return DetailEnrichResult(
            existing.copy(
                merchant = merchant,
                productDesc = productDesc,
                payMethod = payMethod,
                orderId = orderId,
                platformCategory = platformCategory,
                tradeTime = tradeTime,
                date = date,
                dateTime = dateTime,
                sourceId = sourceId,
                updatedAt = nowMs
            ),
            true
        )
    }

    private fun parseMs(text: String): Long? = try {
        LocalDateTime.parse(text, DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
