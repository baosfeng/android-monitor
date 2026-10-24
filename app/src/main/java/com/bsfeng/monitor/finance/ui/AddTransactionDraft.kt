package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的 buildTransaction；职责：把表单字段组装成待保存的 Transaction（编辑保留 id/createdAt/source）。

import com.bsfeng.monitor.finance.model.Transaction

/**
 * 表单字段快照 + 组装成 [Transaction]。
 *
 * 编辑时以原交易为基底 `copy`（保留 id / createdAt / source / 单据字段），新增时新建。
 * 交易时间取用户选定的日期与时刻（不是点保存的时刻）。
 */
internal data class AddTransactionDraft(
    val txType: Int,
    val amountText: String,
    val selectedCategory: String,
    val accountName: String,
    val toAccountName: String,
    val remark: String,
    val tag: String,
    val parts: RecordForm.TimeParts,
    val editing: Transaction?,
    val isRefundEntry: Boolean,
) {

    /** 组装成交易：编辑走 copy、新增走构造，两条路径保留同一套字段口径。 */
    fun build(): Transaction {
        val magnitude = RecordForm.amountOf(amountText)
        val signedAmount = if (isRefundEntry) -magnitude else magnitude
        val now = System.currentTimeMillis()
        // 交易时间 = 用户在表单里选定的日期与时刻（精确到秒），不是「点保存的时刻」——
        // 对账看的就是页面上的支付时间（2026-10 真机踩坑：记 18:32 的那笔被写成了 20:51）
        val timestamp = RecordForm.timestampOf(parts)
        val category = if (txType == RecordForm.TYPE_TRANSFER) "" else selectedCategory
        val toAccount = if (txType == RecordForm.TYPE_TRANSFER) toAccountName else ""
        val base = editing
        return if (base != null) {
            base.copy(
                type = txType,
                categoryName = category,
                amount = signedAmount,
                accountName = accountName,
                toAccountName = toAccount,
                remark = remark,
                tag = tag,
                date = parts.date,
                dateTime = timestamp,
                updatedAt = now,
            )
        } else {
            Transaction(
                type = txType,
                categoryName = category,
                amount = signedAmount,
                accountName = accountName,
                toAccountName = toAccount,
                remark = remark,
                tag = tag,
                date = parts.date,
                dateTime = timestamp,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
