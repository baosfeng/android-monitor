package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt；职责：记账数据访问的统一入口。交易/分类/账户/预算四组读写分别委托给
// FinanceTransactionDao / FinanceCategoryDao / FinanceAccountDao / FinanceBudgetDao，公开方法签名保持不变。

import androidx.core.database.sqlite.transaction
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 记账模块数据访问对象。
 *
 * 所有数据库读写操作集中在此类中，FinanceManager 通过此类访问数据。
 * 实现按实体拆到四个 internal DAO，本类只做同签名转发（调用方与测试无需改 import）。
 */
class FinanceDao(private val dbHelper: FinanceDatabaseHelper) {

    internal val transactionDao = FinanceTransactionDao(dbHelper)
    internal val categoryDao = FinanceCategoryDao(dbHelper)
    internal val accountDao = FinanceAccountDao(dbHelper)
    internal val budgetDao = FinanceBudgetDao(dbHelper)

    // ========== Transaction ==========

    /** 插入一条交易记录 */
    fun insertTransaction(tx: Transaction): Long = transactionDao.insertTransaction(tx)

    /** 批量插入交易记录（事务） */
    fun insertTransactions(txs: List<Transaction>): Int = transactionDao.insertTransactions(txs)

    /** 更新交易记录 */
    fun updateTransaction(tx: Transaction): Int = transactionDao.updateTransaction(tx)

    /** 删除交易记录 */
    fun deleteTransaction(id: Long): Int = transactionDao.deleteTransaction(id)

    /** 按 ID 查询 */
    fun getTransactionById(id: Long): Transaction? = transactionDao.getTransactionById(id)

    /** 按月份查询（按日期降序） */
    fun getTransactionsByMonth(yearMonth: String): List<Transaction> =
        transactionDao.getTransactionsByMonth(yearMonth)

    /** 按日期范围查询 */
    fun getTransactionsByDateRange(startDate: String, endDate: String): List<Transaction> =
        transactionDao.getTransactionsByDateRange(startDate, endDate)

    /** 获取最近的交易记录 */
    fun getRecentTransactions(limit: Int = 50): List<Transaction> = transactionDao.getRecentTransactions(limit)

    /**
     * 自动记账记录里按「来源 + 金额」找候选（交易详情页补全用）。
     *
     * 详情页发生在记账之后，无法用 sourceId 精确定位（结果页往往没有订单号/时间），
     * 因此先按来源（`auto_alipay` / `auto_wechat`）+ 金额捞一小批，再由纯逻辑按时间挑最近的一笔。
     */
    fun findAutoTransactionsByAmount(source: String, amount: Double, limit: Int = 20): List<Transaction> =
        transactionDao.findAutoTransactionsByAmount(source, amount, limit)

    /**
     * 自动记账记录里「说明」非空的全部行（旧版拼接残留清理用，见 [LegacyRemarkCleaner]）。
     *
     * `LIKE 'auto_%'` 覆盖 auto_alipay / auto_wechat；手动记账（manual）与熊猫导入不参与。
     */
    fun getAutoTransactionsWithRemark(): List<Transaction> = transactionDao.getAutoTransactionsWithRemark()

    /** 只清说明列（旧拼接迁移用）：不动类型 / 分类 / 金额 / 余额。 */
    fun clearTransactionRemark(id: Long): Int = transactionDao.clearTransactionRemark(id)

    /**
     * 同一商户最近一笔交易（自动记账「上次选过的类型 / 分类」默认值用）。
     *
     * 精确匹配商户名：自动记账落库的商户来自同一页面，跨次稳定；手工录入的商户名与页面
     * 不一致时自然不命中 —— 宁可回落到解析器默认分类，也不能把别家店的分类套过来。
     */
    fun findLastByMerchant(merchant: String): Transaction? = transactionDao.findLastByMerchant(merchant)

    /** 搜索交易（按说明或分类名模糊匹配） */
    fun searchTransactions(query: String, limit: Int = 50): List<Transaction> =
        transactionDao.searchTransactions(query, limit)

    /** 获取某月的总支出 */
    fun getMonthlyExpenseTotal(yearMonth: String): Double = transactionDao.getMonthlyExpenseTotal(yearMonth)

    /** 获取某月的总收入 */
    fun getMonthlyIncomeTotal(yearMonth: String): Double = transactionDao.getMonthlyIncomeTotal(yearMonth)

    /** 获取某月的分类支出统计 */
    fun getMonthlyCategoryBreakdown(yearMonth: String, type: Int = 0): Map<String, Double> =
        transactionDao.getMonthlyCategoryBreakdown(yearMonth, type)

    /** 获取所有数据的月份列表（去重降序） */
    fun getAvailableMonths(): List<String> = transactionDao.getAvailableMonths()

    /** 检查某笔交易是否已导入（按 source + sourceId 去重） */
    fun transactionExists(source: String, sourceId: String): Boolean =
        transactionDao.transactionExists(source, sourceId)

    // ========== Category ==========

    /**
     * 批量插入分类（去重，事务）。
     *
     * @return 实际新增的分类数量；已存在的同名分类（UNIQUE(name) 冲突）不计入
     */
    fun insertCategories(categories: List<Category>): Int = categoryDao.insertCategories(categories)

    /** 获取所有分类 */
    fun getAllCategories(): List<Category> = categoryDao.getAllCategories()

    /** 某类型的一级分类（parent_id = -1，按 sort_order） */
    fun getTopLevelCategories(type: Int): List<Category> = categoryDao.getTopLevelCategories(type)

    /** 某一级分类下的二级分类（按 sort_order） */
    fun getChildCategories(parentId: Long): List<Category> = categoryDao.getChildCategories(parentId)

    /** 清空分类表（分类体系迁移用，事务内调用） */
    fun clearCategories(): Int = categoryDao.clearCategories()

    /** 把交易里旧的分类名批量改到新分类（迁移用；不动金额/余额/时间） */
    fun updateTransactionCategory(oldName: String, newName: String): Int =
        transactionDao.updateTransactionCategory(oldName, newName)

    /** 把预算里旧的分类名改到新一级（迁移用） */
    fun updateBudgetCategory(oldName: String, newName: String): Int =
        categoryDao.updateBudgetCategory(oldName, newName)

    /** 按类型获取分类 */
    fun getCategoriesByType(type: Int): List<Category> = categoryDao.getCategoriesByType(type)

    /** 插入一个分类并返回新 id（分类体系迁移/种子用；冲突返回 -1） */
    fun insertCategory(category: Category): Long = categoryDao.insertCategory(category)

    /** 按名字取分类 id；不存在返回 -1（分类目录补齐时复用已有分类） */
    fun findCategoryId(name: String): Long = categoryDao.findCategoryId(name)

    /** 交易里出现过的分类名 + 类型（迁移映射用；跳过空分类） */
    fun getDistinctTransactionCategoryTypes(): List<Pair<String, Int>> =
        transactionDao.getDistinctTransactionCategoryTypes()

    /** 预算里出现过的分类名（迁移映射用；总预算空串跳过） */
    fun getDistinctBudgetCategoryNames(): List<String> = categoryDao.getDistinctBudgetCategoryNames()

    // ========== Account ==========

    /** 插入账户（去重） */
    fun insertAccount(account: Account): Long = accountDao.insertAccount(account)

    /** 获取所有账户 */
    fun getAllAccounts(): List<Account> = accountDao.getAllAccounts()

    /** 按名称查询账户 */
    fun getAccountByName(name: String): Account? = accountDao.getAccountByName(name)

    /** 更新账户余额 */
    fun updateAccountBalance(accountId: Long, newBalance: Double): Int =
        accountDao.updateAccountBalance(accountId, newBalance)

    // ========== Budget ==========

    /** 插入或更新预算（按 month + category 唯一约束） */
    fun upsertBudget(budget: Budget): Long = budgetDao.upsertBudget(budget)

    /** 获取某月预算列表 */
    fun getBudgetsByMonth(month: String): List<Budget> = budgetDao.getBudgetsByMonth(month)

    // ========== Helper ==========

    /**
     * 在单个数据库事务中执行 [block]，块内抛出异常时自动回滚。
     *
     * 供批量导入等需要「全部成功或全部回滚」的场景使用；
     * 与块内各方法自己开启的事务可安全嵌套（仅最外层提交生效）。
     */
    fun <T> runInTransaction(block: () -> T): T {
        val db = dbHelper.writableDatabase
        return db.transaction { block() }
    }
}
