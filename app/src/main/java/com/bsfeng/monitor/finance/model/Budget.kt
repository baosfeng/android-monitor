package com.bsfeng.monitor.finance.model

/**
 * 月度预算。
 *
 * @param month yyyy-MM 格式
 * @param categoryName 分类名称, 空字符串表示总预算
 * @param budgetAmount 预算金额
 * @param spentAmount 已支出金额
 */
data class Budget(
    val id: Long = 0,
    val month: String = "",
    val categoryName: String = "",
    val budgetAmount: Double = 0.0,
    val spentAmount: Double = 0.0
) {
    /** 预算使用百分比 0.0 ~ 1.0 */
    val progress: Float get() =
        if (budgetAmount > 0) (spentAmount / budgetAmount).toFloat().coerceIn(0f, 1f) else 0f

    /** 是否超出预算 */
    val isOverBudget: Boolean get() = spentAmount > budgetAmount && budgetAmount > 0

    /** 是否总预算（不限定分类） */
    val isOverall: Boolean get() = categoryName.isBlank()

    val remainingAmount: Double get() = (budgetAmount - spentAmount).coerceAtLeast(0.0)
}
