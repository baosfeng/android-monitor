package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceManager.kt 的统计查询；职责：月度汇总（FinanceStats）与一级分类占比聚合。

import com.bsfeng.monitor.finance.model.FinanceStats

/** 月度统计查询（拆分自 [FinanceManager]，查询顺序与原实现一致） */
internal class FinanceStatsService(private val dao: FinanceDao) {

    /**
     * 月度统计：总额 + 笔数 + 预算进度 + Top 分类。
     *
     * 预算进度按一级汇总（二级归到父级），与统计页/预算口径一致。
     */
    fun monthlyStats(yearMonth: String): FinanceStats {
        val totalExpense = dao.getMonthlyExpenseTotal(yearMonth)
        val totalIncome = dao.getMonthlyIncomeTotal(yearMonth)
        val transactions = dao.getTransactionsByMonth(yearMonth)
        val budgets = dao.getBudgetsByMonth(yearMonth)

        // 找 Top 分类：按一级汇总（二级归到父级），与统计页/预算口径一致
        val breakdown = CategoryTree(dao.getAllCategories())
            .aggregateByTopLevel(dao.getMonthlyCategoryBreakdown(yearMonth, 0))
        val topCategory = if (breakdown.isEmpty()) "" else breakdown.entries.first().key

        // 预算进度
        val overallBudget = budgets.find { it.isOverall }
        val budgetProgress = if (overallBudget != null && overallBudget.budgetAmount > 0) {
            (totalExpense / overallBudget.budgetAmount).toFloat().coerceIn(0f, 1f)
        } else 0f

        return FinanceStats(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            transactionCount = transactions.size,
            budgetProgress = budgetProgress,
            topCategory = topCategory
        )
    }

    /** 分类占比（一律归到一级）：环形图/图例只出现一级名 */
    fun categoryBreakdown(yearMonth: String, type: Int = 0): Map<String, Double> =
        // 交易可能存二级名（电商）或一级名（购物）：统一归到一级，环形图/图例只出现一级
        CategoryTree(dao.getAllCategories())
            .aggregateByTopLevel(dao.getMonthlyCategoryBreakdown(yearMonth, type))
}
