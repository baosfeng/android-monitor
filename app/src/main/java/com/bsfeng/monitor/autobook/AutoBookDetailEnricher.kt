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
 * 补全规则：**只补信息，不改用户的选择** —— 类型 / 分类 / 金额 / 账户原样保留；
 * 商户名只在详情页给的更全（不更短）时替换；备注只追加缺失的商品说明 / 付款方式。
 */
internal object AutoBookDetailEnricher {

    /**
     * 精确匹配窗口：详情页时间与已记账记录时间几乎一致（补全过详情后 dateTime 就是页面原文）。
     *
     * 用来在**同金额多笔**（真机 16:49 / 16:51 两个 0.01 红包）时避免把另一笔当成这一笔。
     */
    const val EXACT_WINDOW_MS = 60 * 1000L

    /** 模糊匹配窗口：**只对还没补全过详情的记录**生效（记账时刻与页面时间可能差几分钟） */
    const val MATCH_WINDOW_MS = 30 * 60 * 1000L

    /** 详情页没给时间时的兜底范围：只和最近 31 天的记录匹配 */
    const val FALLBACK_WINDOW_MS = 31L * 24 * 60 * 60 * 1000

    /** 金额比较容差：两端都是页面上解析出来的两位小数，浮点误差远小于它 */
    private const val AMOUNT_EPS = 0.005

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 从候选记录里找这笔详情页对应的一条；找不到返回 null（调用方保持原来的弹窗行为）。 */
    fun findMatch(candidates: List<Transaction>, draft: AutoBookDraft, nowMs: Long): Transaction? {
        val pool = candidates.filter { abs(it.amount - draft.amount) < AMOUNT_EPS }
        if (pool.isEmpty()) return null

        val normalized = AutoBookParser.normalizeTradeTime(draft.timeText)
        val detailMs = normalized?.let { parseMs(it) }
        if (normalized != null && detailMs != null) {
            // 1) 时间几乎一致：这次就是那一笔（即使已经补全过详情也要认，否则重进详情页会重复弹窗）
            val exact = pool
                .mapNotNull { tx -> parseMs(tx.dateTime)?.let { ms -> tx to abs(ms - detailMs) } }
                .filter { it.second <= EXACT_WINDOW_MS }
                .minByOrNull { it.second }
                ?.first
            if (exact != null) return exact

            // 2) 模糊匹配只对「还没补全过详情」的记录生效：同金额的第二笔不能匹配到已补全的那笔。
            val pending = pool.filter { it.isUnenriched() }
            val nearest = pending
                .mapNotNull { tx -> parseMs(tx.dateTime)?.let { ms -> tx to abs(ms - detailMs) } }
                .filter { it.second <= MATCH_WINDOW_MS }
                .minByOrNull { it.second }
                ?.first
            if (nearest != null) return nearest

            // 3) 记录当时取的是记账时刻（可能差到几小时）→ 退到同一天 + 金额，且同样只认未补全的
            val date = normalized.substringBefore(" ")
            return pending.filter { it.date == date }.maxByOrNull { it.createdAt }
        }

        return pool
            .filter { it.isUnenriched() && nowMs - it.createdAt <= FALLBACK_WINDOW_MS }
            .maxByOrNull { it.createdAt }
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

        // 备注：用户填的文字原样保留，只追加详情页新增的商品说明 / 付款方式
        var remark = existing.remark
        if (productDesc.isNotBlank() && !remark.contains(productDesc)) {
            remark = appendPart(remark, productDesc)
            changed = true
        }
        if (payMethod.isNotBlank() && !remark.contains(payMethod)) {
            remark = appendPart(remark, payMethod)
            changed = true
        }

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
                remark = remark.take(AutoBookDraft.REMARK_MAX_LENGTH),
                sourceId = sourceId,
                updatedAt = nowMs
            ),
            true
        )
    }

    private fun appendPart(remark: String, part: String): String =
        if (remark.isBlank()) part else "$remark·$part"

    private fun parseMs(text: String): Long? = try {
        LocalDateTime.parse(text, DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
