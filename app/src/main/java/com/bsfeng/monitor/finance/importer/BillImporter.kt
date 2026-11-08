package com.bsfeng.monitor.finance.importer

// 账单入库：解析产出的 BillRecord → 账本行（查重 / 只补空 / 插入，单事务）。
// 设计真相源见 docs/记账/账单导入.md（身份键、更新语义、分类推断三节）。

import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 账单文件导入（支付宝 / 微信导出的 csv / xlsx / zip）。
 *
 * 三条不变式：
 * 1. **身份键不含商户名**（见 [BillIdentity]），同一笔不会被记两次；
 * 2. **跨源去重**：自动记账已写过的 auto_alipay 与导入写的 alipay_import 是同一笔，先按订单号全库找；
 * 3. **只补空不覆盖**：命中已有记录时只填空字段，用户改过的分类 / 说明 / 金额原样保留。
 */
class BillImporter(private val financeManager: FinanceManager) {

    /**
     * 导入一个账单文件。
     *
     * @param fileName 原始文件名（渠道识别用）
     * @param bytes 文件字节（解析侧负责编码探测）
     * @return 统计结果；[BillImportResult.errors] 是失败明细（行号 + 原因）
     */
    suspend fun import(fileName: String, bytes: ByteArray): BillImportResult = withContext(Dispatchers.IO) {
        // 解析与写库都在 IO 线程：调用方（viewModelScope.async）默认落在 Main，
        // 几 MB 的 csv 解压 + GBK 解码 + 上千行插入若留在主线程会直接卡住进度条
        val records = try {
            BillFileParser.parse(fileName, bytes)
        } catch (e: IllegalArgumentException) {
            // 解析失败（空文件 / 表头识别不出）不该让导入页崩，转成可展示的失败明细
            return@withContext BillImportResult(failed = 1, errors = listOf(e.message ?: "账单解析失败"))
        }
        importRecords(records)
    }

    /**
     * 入库已解析好的记录（单测直接调它，绕开文件解析）。
     *
     * 整体一个事务：中途抛异常全部回滚，账本不会只进一半。
     */
    internal fun importRecords(records: List<BillRecord>): BillImportResult {
        if (records.isEmpty()) return BillImportResult()
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0
        var resolved = 0
        val errors = mutableListOf<String>()
        val dao = financeManager.dao

        dao.runInTransaction {
            for ((index, record) in records.withIndex()) {
                if (record.direction == BillDirection.SKIP) {
                    skipped++
                    continue
                }
                val lineNo = if (record.lineNo > 0) record.lineNo else index + 1
                val sourceId = BillIdentity.sourceId(record)
                if (sourceId == null) {
                    errors += "第 " + lineNo + " 行缺少交易单号与时间，已跳过"
                    continue
                }
                // 身份键成立（有单号）但时间解析不出：date 列一旦写成空串，
                // 这笔账在「最近列表」里看得见，却因为月度统计/预算都按 date 字符串区间比较
                // 而永远不进任何月份 —— 隐形账目比丢掉一行更危险，所以计入错误明细并跳过。
                if (BillIdentity.dateOf(record).isEmpty()) {
                    errors += "第 " + lineNo + " 行交易时间无法识别（「" +
                        excerpt(record.tradeTime) + "」），已跳过"
                    continue
                }
                val existing = findExisting(record, sourceId)
                if (existing == null) {
                    val category = BillCategoryResolver.resolve(record)
                    // 走 FinanceManager 的写入口：交易写库与账户余额变更同一条路径（同一个事务），
                    // 直接调 dao.insertTransaction 会让这笔导入永远不进「支付宝/微信钱包」余额
                    financeManager.insertTransactionWithEffect(buildTransaction(record, sourceId, category))
                    inserted++
                    if (!BillCategoryResolver.isFallback(category)) resolved++
                } else if (fillBlanks(existing, record, sourceId)) {
                    updated++
                } else {
                    unchanged++
                }
            }
        }

        val result = BillImportResult(
            totalRows = records.size,
            inserted = inserted,
            updated = updated,
            unchanged = unchanged,
            skipped = skipped,
            failed = errors.size,
            categoriesResolved = resolved,
            errors = errors,
        )
        if (result.hasWrites) financeManager.notifyChanged()
        return result
    }

    /**
     * 查重：先按 (来源, 身份键) 精确找，再按订单号**跨源**找。
     *
     * 跨源那一档是必须的：自动记账写 auto_alipay，导入写 alipay_import，
     * 只比来源的话同一笔会在账本里出现两次（见 docs「跨源去重」）。
     */
    private fun findExisting(record: BillRecord, sourceId: String): Transaction? {
        val exact = financeManager.dao.findBySourceId(record.channel.sourcePrefix, sourceId)
        if (exact != null) return exact
        val orderId = record.orderId.trim()
        if (orderId.isEmpty()) return null
        return financeManager.dao.findByOrderId(orderId)
    }

    /** 新插入的一行：金额取绝对值，方向由 type 表达，日期取页面交易时间 */
    private fun buildTransaction(record: BillRecord, sourceId: String, category: String): Transaction {
        val date = BillIdentity.dateOf(record)
        val dateTime = BillIdentity.dateTimeOf(record)
        return Transaction(
            type = if (record.direction == BillDirection.INCOME) 1 else 0,
            categoryName = category,
            amount = kotlin.math.abs(record.amount),
            accountName = accountOf(record),
            date = date,
            dateTime = dateTime,
            source = record.channel.sourcePrefix,
            sourceId = sourceId,
            merchant = record.counterparty.trim(),
            productDesc = record.product.trim(),
            payMethod = record.payMethod.trim(),
            orderId = record.orderId.trim(),
            platformCategory = record.platformCategory.trim(),
            tradeTime = record.tradeTime.trim(),
        )
    }

    /**
     * 只补空：命中已有记录时，只把**当前为空**的字段填上。
     *
     * 补的只有这五个（与 docs「更新语义」一致）：商户 / 商品说明 / 付款方式 / 订单号 / 平台分类。
     * 不覆盖 amount / categoryName / remark / date / dateTime —— 用户可能已经改过分类或写了说明，
     * 每次导入都改回去等于撤销他的编辑。
     * 例外：orderId 与 sourceId 是身份不是用户内容，一定写入。
     *
     * @return 是否真的有变化（false 时调用方计入 unchanged）
     */
    private fun fillBlanks(existing: Transaction, record: BillRecord, sourceId: String): Boolean {
        val merchant = if (existing.merchant.isBlank()) record.counterparty.trim() else existing.merchant
        val productDesc = if (existing.productDesc.isBlank()) record.product.trim() else existing.productDesc
        val payMethod = if (existing.payMethod.isBlank()) record.payMethod.trim() else existing.payMethod
        val orderId = record.orderId.trim().ifEmpty { existing.orderId }
        val platformCategory =
            if (existing.platformCategory.isBlank()) record.platformCategory.trim() else existing.platformCategory
        val sourceIdNew = existing.sourceId.ifBlank { sourceId }

        val changed = merchant != existing.merchant ||
            productDesc != existing.productDesc ||
            payMethod != existing.payMethod ||
            orderId != existing.orderId ||
            platformCategory != existing.platformCategory ||
            sourceIdNew != existing.sourceId
        if (!changed) return false

        financeManager.dao.updateTransaction(
            existing.copy(
                merchant = merchant,
                productDesc = productDesc,
                payMethod = payMethod,
                orderId = orderId,
                platformCategory = platformCategory,
                sourceId = sourceIdNew,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 错误明细里的原文摘录：超过 20 字截断，避免整段异常文本把结果卡刷满。 */
    private fun excerpt(raw: String): String {
        val text = raw.trim()
        return if (text.length <= 20) text else text.take(20) + "…"
    }

    /** 记账账户按渠道固定：支付宝 → 支付宝；微信 → 微信钱包（余额按账户算，不能留空） */
    private fun accountOf(record: BillRecord): String = when (record.channel) {
        BillChannel.WECHAT -> "微信钱包"
        else -> "支付宝"
    }
}