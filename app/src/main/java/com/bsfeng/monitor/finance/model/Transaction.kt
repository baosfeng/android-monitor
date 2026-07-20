package com.bsfeng.monitor.finance.model

/**
 * 单笔交易记录数据类。
 *
 * @param type 0=支出, 1=收入, 2=转账, 3=退款
 * @param source 来源标记, 如 "manual"（手动录入）, "panda_import"（熊猫记账导入）
 */
data class Transaction(
    val id: Long = 0,
    val type: Int = 0,
    val categoryName: String = "",
    val amount: Double = 0.0,
    val accountName: String = "",
    val remark: String = "",
    val tag: String = "",
    val date: String = "",
    val dateTime: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "manual",
    val sourceId: String = ""
) {
    val isExpense: Boolean get() = type == 0
    val isIncome: Boolean get() = type == 1
    val isTransfer: Boolean get() = type == 2
    val isRefund: Boolean get() = type == 3
    val displayAmount: String get() = String.format("%.2f", amount)
    val signedAmount: Double get() = if (isExpense || isRefund) -amount else amount
}
