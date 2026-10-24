package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceManager.kt 的预算查询与写入；职责：用当月实际支出回填预算进度、写入预算。

import com.bsfeng.monitor.finance.model.Budget

/** 预算业务逻辑（拆分自 [FinanceManager]） */
internal class FinanceBudgetService(private val dao: FinanceDao) {

    /**
     * 获取某月预算，并用当月**实际支出**回填 [Budget.spentAmount]。
     *
     * 存储中的 spent_amount 是导入时的历史快照（熊猫记账的 pay 字段），
     * 若直接使用，预算进度条会长期停在陈旧值上。
     */
    fun monthlyBudget(yearMonth: String, typeExpense: Int): List<Budget> {
        val budgets = dao.getBudgetsByMonth(yearMonth)
        if (budgets.isEmpty()) return emptyList()

        val expenseTotal = dao.getMonthlyExpenseTotal(yearMonth)
        // 预算按一级：二级支出（电商）要先归到父级（购物）才能对上分类预算行
        val breakdown = CategoryTree(dao.getAllCategories())
            .aggregateByTopLevel(dao.getMonthlyCategoryBreakdown(yearMonth, typeExpense))
        return budgets.map { budget ->
            val spent = if (budget.isOverall) expenseTotal
            else breakdown[budget.categoryName] ?: 0.0
            budget.copy(spentAmount = spent)
        }
    }

    /** 写入某月某分类预算；保留已有的 spentAmount 快照 */
    fun setBudget(month: String, categoryName: String, amount: Double) {
        val existing = dao.getBudgetsByMonth(month)
        val spent = existing.find { it.categoryName == categoryName }?.spentAmount ?: 0.0
        dao.upsertBudget(Budget(month = month, categoryName = categoryName, budgetAmount = amount, spentAmount = spent))
    }
}
