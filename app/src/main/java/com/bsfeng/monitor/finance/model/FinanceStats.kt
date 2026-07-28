package com.bsfeng.monitor.finance.model

/**
 * 月度统计数据。
 */
data class FinanceStats(
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val transactionCount: Int = 0,
    val budgetProgress: Float = 0f,
    val topCategory: String = ""
) {
    /** 结余（收入 - 支出） */
    val balance: Double get() = totalIncome - totalExpense

    /** 预算是否超支 */
    val isOverBudget: Boolean get() = budgetProgress >= 1f

    val displayIncome: String get() = String.format(java.util.Locale.CHINA, "%.2f", totalIncome)
    val displayExpense: String get() = String.format(java.util.Locale.CHINA, "%.2f", totalExpense)
    val displayBalance: String get() = String.format(java.util.Locale.CHINA, "%.2f", balance)
}
