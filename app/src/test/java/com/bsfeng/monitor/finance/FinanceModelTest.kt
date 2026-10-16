package com.bsfeng.monitor.finance

import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 记账领域模型计算属性测试（纯 JVM，不依赖 Android）。
 *
 * 覆盖正常路径、边界条件与退款/超支等特殊场景。
 */
class FinanceModelTest {

    // ==================== Transaction ====================

    @Test
    fun `支出交易：展示金额为正数，带符号金额为负`() {
        val tx = Transaction(type = 0, amount = 12.5)
        assertTrue(tx.isExpense)
        assertFalse(tx.isRefundEntry)
        assertEquals("12.50", tx.displayAmount)
        assertEquals(-12.5, tx.signedAmount, 0.0001)
    }

    @Test
    fun `退款交易：负数支出被识别为退款抵扣，展示金额取绝对值`() {
        val tx = Transaction(type = 0, amount = -30.0)
        assertTrue(tx.isExpense)
        assertTrue(tx.isRefundEntry)
        assertEquals("避免出现 -¥-30.00", "30.00", tx.displayAmount)
        assertEquals(30.0, tx.signedAmount, 0.0001)
    }

    @Test
    fun `收入交易：带符号金额为正`() {
        val tx = Transaction(type = 1, amount = 8800.0)
        assertTrue(tx.isIncome)
        assertFalse(tx.isRefundEntry)
        assertEquals(8800.0, tx.signedAmount, 0.0001)
    }

    @Test
    fun `金额为零的支出：不视为退款`() {
        val tx = Transaction(type = 0, amount = 0.0)
        assertFalse(tx.isRefundEntry)
        assertEquals("0.00", tx.displayAmount)
    }

    @Test
    fun `转账与退款类型判定`() {
        assertTrue(Transaction(type = 2).isTransfer)
        assertTrue(Transaction(type = 3).isRefund)
    }

    // ==================== Budget ====================

    @Test
    fun `预算进度按已支出除以预算额计算`() {
        val budget = Budget(month = "2026-01", budgetAmount = 1000.0, spentAmount = 250.0)
        assertEquals(0.25f, budget.progress, 0.0001f)
        assertEquals(750.0, budget.remainingAmount, 0.0001)
        assertFalse(budget.isOverBudget)
    }

    @Test
    fun `预算为零时进度为零且不判定超支`() {
        val budget = Budget(month = "2026-01", budgetAmount = 0.0, spentAmount = 500.0)
        assertEquals(0f, budget.progress, 0.0001f)
        assertFalse(budget.isOverBudget)
        assertEquals(0.0, budget.remainingAmount, 0.0001)
    }

    @Test
    fun `已支出超过预算：判定超支且进度封顶为 1`() {
        val budget = Budget(month = "2026-01", budgetAmount = 100.0, spentAmount = 180.0)
        assertTrue(budget.isOverBudget)
        assertEquals(1f, budget.progress, 0.0001f)
    }

    @Test
    fun `分类名为空视为总预算`() {
        assertTrue(Budget(categoryName = "").isOverall)
        assertFalse(Budget(categoryName = "餐饮").isOverall)
    }

    // ==================== FinanceStats ====================

    @Test
    fun `结余等于收入减支出`() {
        val stats = FinanceStats(totalIncome = 1000.0, totalExpense = 300.0, transactionCount = 5)
        assertEquals(700.0, stats.balance, 0.0001)
        assertEquals("700.00", stats.displayBalance)
        assertFalse(stats.isOverBudget)
    }

    @Test
    fun `预算进度打满视为超支`() {
        assertTrue(FinanceStats(budgetProgress = 1f).isOverBudget)
        assertFalse(FinanceStats(budgetProgress = 0.99f).isOverBudget)
    }

    @Test
    fun `支出大于收入时结余为负值`() {
        val stats = FinanceStats(totalIncome = 100.0, totalExpense = 350.0)
        assertEquals(-250.0, stats.balance, 0.0001)
        assertEquals("-250.00", stats.displayBalance)
    }

    // ==================== Account / Category ====================

    @Test
    fun `账户余额格式化保留两位小数`() {
        assertEquals("1234.50", Account(balance = 1234.5).displayBalance)
        assertEquals("0.00", Account(balance = 0.0).displayBalance)
    }

    @Test
    fun `账户类型判定`() {
        assertTrue(Account(type = Account.TYPE_CASH).isCash)
        assertTrue(Account(type = Account.TYPE_BANK_CARD).isBankCard)
        assertTrue(Account(type = Account.TYPE_THIRD_PARTY).isThirdParty)
    }

    // ==================== 交易时间戳（崩溃回归测试） ====================

    @Test
    fun `交易时间戳拼接日期与时刻`() {
        val now = LocalDateTime.of(2026, 10, 14, 9, 5, 7)
        assertEquals("2026-10-14 09:05:07", Transaction.buildDateTime("2026-10-14", now))
    }

    @Test
    fun `交易时间戳边界取自午夜与一天最后一秒`() {
        assertEquals(
            "2026-10-14 00:00:00",
            Transaction.buildDateTime("2026-10-14", LocalDateTime.of(2026, 10, 14, 0, 0, 0)),
        )
        assertEquals(
            "2026-10-14 23:59:59",
            Transaction.buildDateTime("2026-10-14", LocalDateTime.of(2026, 10, 14, 23, 59, 59)),
        )
    }

    @Test
    fun `交易时间戳支持跨日组合且不抛 UnsupportedTemporalTypeException`() {
        // 回归：曾用 LocalDate.now().format("HH:mm") 取时刻，
        // 抛 UnsupportedTemporalTypeException 导致保存交易时 App 崩溃
        val now = LocalDateTime.of(2026, 1, 1, 8, 30, 15)
        assertEquals("2025-12-31 08:30:15", Transaction.buildDateTime("2025-12-31", now))
    }

    @Test
    fun `交易时间戳精确到秒且默认使用当前时刻`() {
        val text = Transaction.buildDateTime("2026-10-14")
        assertTrue(
            "格式应为 yyyy-MM-dd HH:mm:ss（精确到秒），实际: $text",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(text),
        )
        assertTrue(text.startsWith("2026-10-14 "))
    }

    @Test
    fun `分类类型判定与默认分类非空`() {
        assertTrue(Category(type = 0).isExpenseType)
        assertTrue(Category(type = 1).isIncomeType)
        assertTrue(Category.defaultExpenseCategories.isNotEmpty())
        assertTrue(Category.defaultIncomeCategories.isNotEmpty())
    }
}
