package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记账模块业务逻辑统一入口。
 *
 * 提供协程友好的 API，所有 IO 操作在 Dispatchers.IO 执行。
 * 通过 FinanceDao 访问数据，追加业务规则。
 */
class FinanceManager private constructor(context: Context) {

    private val dbHelper = FinanceDatabaseHelper.getInstance(context)
    val dao = FinanceDao(dbHelper)

    // ========== 交易 ==========

    suspend fun addTransaction(tx: Transaction): Long = withContext(Dispatchers.IO) {
        dao.insertTransaction(tx)
    }

    suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteTransaction(id)
    }

    suspend fun updateTransaction(tx: Transaction) = withContext(Dispatchers.IO) {
        dao.updateTransaction(tx)
    }

    suspend fun getTransactionById(id: Long): Transaction? = withContext(Dispatchers.IO) {
        dao.getTransactionById(id)
    }

    suspend fun getMonthlyTransactions(yearMonth: String): List<Transaction> = withContext(Dispatchers.IO) {
        dao.getTransactionsByMonth(yearMonth)
    }

    suspend fun getRecentTransactions(limit: Int = 50): List<Transaction> = withContext(Dispatchers.IO) {
        dao.getRecentTransactions(limit)
    }

    suspend fun searchTransactions(query: String, limit: Int = 50): List<Transaction> = withContext(Dispatchers.IO) {
        dao.searchTransactions(query, limit)
    }

    // ========== 统计 ==========

    suspend fun getMonthlyStats(yearMonth: String): FinanceStats = withContext(Dispatchers.IO) {
        val totalExpense = dao.getMonthlyExpenseTotal(yearMonth)
        val totalIncome = dao.getMonthlyIncomeTotal(yearMonth)
        val transactions = dao.getTransactionsByMonth(yearMonth)
        val budgets = dao.getBudgetsByMonth(yearMonth)

        // 找 Top 分类
        val breakdown = dao.getMonthlyCategoryBreakdown(yearMonth, 0)
        val topCategory = if (breakdown.isEmpty()) "" else breakdown.entries.first().key

        // 预算进度
        val overallBudget = budgets.find { it.isOverall }
        val budgetProgress = if (overallBudget != null && overallBudget.budgetAmount > 0) {
            (totalExpense / overallBudget.budgetAmount).toFloat().coerceIn(0f, 1f)
        } else 0f

        FinanceStats(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            transactionCount = transactions.size,
            budgetProgress = budgetProgress,
            topCategory = topCategory
        )
    }

    suspend fun getCategoryBreakdown(yearMonth: String, type: Int = 0): Map<String, Double> = withContext(Dispatchers.IO) {
        dao.getMonthlyCategoryBreakdown(yearMonth, type)
    }

    suspend fun getAvailableMonths(): List<String> = withContext(Dispatchers.IO) {
        dao.getAvailableMonths()
    }

    // ========== 分类 ==========

    suspend fun getAllCategories(): List<Category> = withContext(Dispatchers.IO) {
        dao.getAllCategories()
    }

    suspend fun getCategoriesByType(type: Int): List<Category> = withContext(Dispatchers.IO) {
        dao.getCategoriesByType(type)
    }

    suspend fun seedDefaultCategories() = withContext(Dispatchers.IO) {
        val existing = dao.getAllCategories()
        if (existing.isNotEmpty()) return@withContext

        val expenseCats = Category.defaultExpenseCategories.mapIndexed { index, name ->
            Category(name = name, type = 0, sortOrder = index)
        }
        val incomeCats = Category.defaultIncomeCategories.mapIndexed { index, name ->
            Category(name = name, type = 1, sortOrder = index)
        }
        dao.insertCategories(expenseCats + incomeCats)
    }

    suspend fun seedDefaultAccounts() = withContext(Dispatchers.IO) {
        val existing = dao.getAllAccounts()
        if (existing.isNotEmpty()) return@withContext

        for ((index, acc) in Account.defaultAccounts.withIndex()) {
            dao.insertAccount(acc.copy(sortOrder = index))
        }
    }

    // ========== 账户 ==========

    suspend fun getAllAccounts(): List<Account> = withContext(Dispatchers.IO) {
        dao.getAllAccounts()
    }

    suspend fun getAccountByName(name: String): Account? = withContext(Dispatchers.IO) {
        dao.getAccountByName(name)
    }

    // ========== 预算 ==========

    suspend fun getMonthlyBudget(yearMonth: String): List<Budget> = withContext(Dispatchers.IO) {
        dao.getBudgetsByMonth(yearMonth)
    }

    suspend fun setBudget(month: String, categoryName: String, amount: Double) = withContext(Dispatchers.IO) {
        val existing = dao.getBudgetsByMonth(month)
        val spent = existing.find { it.categoryName == categoryName }?.spentAmount ?: 0.0
        dao.upsertBudget(Budget(month = month, categoryName = categoryName, budgetAmount = amount, spentAmount = spent))
    }

    // ========== 数据完整性 ==========

    /** 初始化：确保分类和账户种子数据存在 */
    suspend fun ensureInitialized() {
        seedDefaultCategories()
        seedDefaultAccounts()
    }

    companion object {
        @Volatile
        private var INSTANCE: FinanceManager? = null

        /**
         * 获取单例。注意：种子数据初始化（[ensureInitialized]）由调用方在协程中
         * 显式执行，不在主线程同步建库，避免首次进入记账页卡顿。
         */
        fun getInstance(context: Context): FinanceManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FinanceManager(context.applicationContext)
            }
    }
}
