package com.bsfeng.monitor.finance

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 记账模块业务逻辑统一入口。
 *
 * 提供协程友好的 API，所有 IO 操作在 Dispatchers.IO 执行。
 * 通过 FinanceDao 访问数据，追加业务规则。
 */
class FinanceManager internal constructor(context: Context) {

    private val dbHelper = FinanceDatabaseHelper.getInstance(context)
    val dao = FinanceDao(dbHelper)

    /** 小额迁移标记（当前只用于「旧版自动记账拼接说明」的一次性清理） */
    private val prefs =
        context.applicationContext.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE)

    private val mutationSignal = FinanceMutationSignal()

    /**
     * 进程内数据变更信号（[FinanceMutationSignal.version]）。
     *
     * 所有成功的写操作都会 bump，UI 订阅它重新加载当前月；自动记账与首页共用同一个
     * Manager 单例，所以无障碍服务落库后切回记账 Tab / App 回前台即可见（见 docs/记账/概述.md）。
     */
    val changeVersion: StateFlow<Long> = mutationSignal.version

    /** 写操作成功后统一从这里通知订阅方；读路径不调用，避免「加载 → 再加载」自激。 */
    internal fun notifyChanged() {
        mutationSignal.bump()
    }

    // ========== 交易 ==========

    suspend fun addTransaction(tx: Transaction): Long = withContext(Dispatchers.IO) {
        val id = dao.insertTransaction(tx)
        applyTransactionEffect(tx, sign = 1.0)
        notifyChanged()
        id
    }

    suspend fun deleteTransaction(id: Long): Unit = withContext(Dispatchers.IO) {
        val existing = dao.getTransactionById(id)
        dao.deleteTransaction(id)
        if (existing != null) applyTransactionEffect(existing, sign = -1.0)
        notifyChanged()
    }

    suspend fun updateTransaction(tx: Transaction): Unit = withContext(Dispatchers.IO) {
        val old = dao.getTransactionById(tx.id)
        dao.updateTransaction(tx)
        // 先按旧值回冲、再按新值计入：换账户 / 改金额 / 支出↔转账互转都由这一条路径覆盖
        if (old != null) applyTransactionEffect(old, sign = -1.0)
        applyTransactionEffect(tx, sign = 1.0)
        notifyChanged()
    }

    /** 交易对账户余额的影响（收入为正、支出为负；退款交易 amount 本身为负，故回冲为正）。 */
    private fun balanceEffectOf(tx: Transaction): Double = if (tx.isIncome) tx.amount else -tx.amount

    /**
     * 把一笔交易对余额的影响作用于账户（[sign] = 1 计入、-1 回冲）。
     *
     * 转账（[Transaction.isTransfer]）是**两个账户的一进一出**：转出账户扣、转入账户加。
     * 转入账户缺失（老记录 / 用户未选）时只动转出侧，不让差额凭空落到别的账户。
     */
    private suspend fun applyTransactionEffect(tx: Transaction, sign: Double) {
        if (tx.isTransfer) {
            applyBalanceDelta(tx.accountName, -tx.amount * sign)
            applyBalanceDelta(tx.toAccountName, tx.amount * sign)
            return
        }
        applyBalanceDelta(tx.accountName, balanceEffectOf(tx) * sign)
    }

    /**
     * 账户余额随交易增减（接上原先零调用的 [FinanceDao.updateAccountBalance]）。
     *
     * 口径：种子账户从 0 起算，导入账户以熊猫记账 t_card.money 为快照起点，
     * 之后由本 App 记录的交易按签名金额增减；账户名对不上（空串 / 账户已删）时跳过。
     */
    private suspend fun applyBalanceDelta(accountName: String, delta: Double) {
        if (accountName.isBlank() || delta == 0.0) return
        val account = dao.getAccountByName(accountName) ?: return
        dao.updateAccountBalance(account.id, account.balance + delta)
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

    /** 同一商户最近一笔交易：自动记账记住上次选的类型 / 分类 */
    suspend fun getLastTransactionByMerchant(merchant: String): Transaction? = withContext(Dispatchers.IO) {
        dao.findLastByMerchant(merchant)
    }

    // ========== 统计 ==========

    suspend fun getMonthlyStats(yearMonth: String): FinanceStats = withContext(Dispatchers.IO) {
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

        FinanceStats(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            transactionCount = transactions.size,
            budgetProgress = budgetProgress,
            topCategory = topCategory
        )
    }

    suspend fun getCategoryBreakdown(yearMonth: String, type: Int = 0): Map<String, Double> = withContext(Dispatchers.IO) {
        // 交易可能存二级名（电商）或一级名（购物）：统一归到一级，环形图/图例只出现一级
        CategoryTree(dao.getAllCategories())
            .aggregateByTopLevel(dao.getMonthlyCategoryBreakdown(yearMonth, type))
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
        if (dao.getAllCategories().isNotEmpty()) return@withContext
        insertCatalog()
    }

    /** 插入一级 + 二级分类（调用方负责事务；一级先插拿到 id，二级挂 parentId） */
    private fun insertCatalog() {
        for ((type, seeds) in listOf(0 to CategoryCatalog.expense, 1 to CategoryCatalog.income)) {
            seeds.forEachIndexed { index, seed ->
                val topId = dao.insertCategory(
                    Category(name = seed.name, type = type, parentId = -1, sortOrder = index)
                )
                if (topId > 0) {
                    seed.children.forEachIndexed { childIndex, child ->
                        dao.insertCategory(
                            Category(
                                name = child,
                                type = type,
                                parentId = topId,
                                sortOrder = childIndex,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 分类体系一次性迁移（旧扁平 → 新一级/二级）。
     *
     * 事务内：清空分类表 → 插入新体系 → 交易/预算按 [CategoryMigration] 归到一级。
     * 用落盘标记保证只跑一次；失败整体回滚，旧分类与账目保持原样，下次启动重试。
     */
    suspend fun migrateCategoryTreeOnce() {
        if (prefs.getBoolean(KEY_CATEGORY_TREE, false)) return
        migrateCategoryTree()
        prefs.edit { putBoolean(KEY_CATEGORY_TREE, true) }
    }

    /**
     * 执行分类体系迁移（幂等，可重复调用；单测直接调它）。
     *
     * @return 改写的交易/预算行数
     */
    internal suspend fun migrateCategoryTree(): Int = withContext(Dispatchers.IO) {
        val updated = dao.runInTransaction {
            dao.clearCategories()
            insertCatalog()
            var count = 0
            for ((name, type) in dao.getDistinctTransactionCategoryTypes()) {
                val target = CategoryMigration.mapLegacy(name, type)
                count += dao.updateTransactionCategory(name, target)
            }
            // 预算只有支出分类（总预算 category_name 为空，查询已跳过）
            for (name in dao.getDistinctBudgetCategoryNames()) {
                count += dao.updateBudgetCategory(name, CategoryMigration.mapLegacy(name, 0))
            }
            count
        }
        if (updated > 0) notifyChanged()
        updated
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

    /**
     * 获取某月预算，并用当月**实际支出**回填 [Budget.spentAmount]。
     *
     * 存储中的 spent_amount 是导入时的历史快照（熊猫记账的 pay 字段），
     * 若直接使用，预算进度条会长期停在陈旧值上。
     */
    suspend fun getMonthlyBudget(yearMonth: String): List<Budget> = withContext(Dispatchers.IO) {
        val budgets = dao.getBudgetsByMonth(yearMonth)
        if (budgets.isEmpty()) return@withContext emptyList()

        val expenseTotal = dao.getMonthlyExpenseTotal(yearMonth)
        // 预算按一级：二级支出（电商）要先归到父级（购物）才能对上分类预算行
        val breakdown = CategoryTree(dao.getAllCategories())
            .aggregateByTopLevel(dao.getMonthlyCategoryBreakdown(yearMonth, TYPE_EXPENSE))
        budgets.map { budget ->
            val spent = if (budget.isOverall) expenseTotal
            else breakdown[budget.categoryName] ?: 0.0
            budget.copy(spentAmount = spent)
        }
    }

    suspend fun setBudget(month: String, categoryName: String, amount: Double): Unit = withContext(Dispatchers.IO) {
        val existing = dao.getBudgetsByMonth(month)
        val spent = existing.find { it.categoryName == categoryName }?.spentAmount ?: 0.0
        dao.upsertBudget(Budget(month = month, categoryName = categoryName, budgetAmount = amount, spentAmount = spent))
        notifyChanged()
    }

    // ========== 数据完整性 ==========

    /** 初始化：分类体系迁移（只跑一次）→ 种子兜底 → 其他一次性清理 */
    suspend fun ensureInitialized() {
        migrateCategoryTreeOnce()
        seedDefaultCategories()
        seedDefaultAccounts()
        cleanLegacyAggregatedRemarksOnce()
    }

    /**
     * 旧版自动记账把「商户·商品说明·付款方式」拼进 remark；2026-10-20 起「说明」只存用户手写内容。
     *
     * 只跑一次：按旧算法精确重算并比对，能确定是拼接产生的说明才清空（见 [LegacyRemarkCleaner]）。
     * 用标记落盘避免每次启动扫全表；迁移失败不影响 App 启动。
     */
    private suspend fun cleanLegacyAggregatedRemarksOnce() {
        if (prefs.getBoolean(KEY_LEGACY_REMARK_CLEANED, false)) return
        val cleaned = cleanLegacyAggregatedRemarks()
        prefs.edit { putBoolean(KEY_LEGACY_REMARK_CLEANED, true) }
        if (cleaned > 0) {
            android.util.Log.i(
                TAG,
                "已清理 $cleaned 条旧版自动记账拼接说明（单据信息保留在商户 / 商品说明 / 付款方式字段）"
            )
        }
    }

    /**
     * 清理旧版拼接说明（幂等，可重复调用；单测直接调它）。
     *
     * 判定完全在 [LegacyRemarkCleaner] 的纯逻辑里：只有 `source` 以 `auto_` 开头、
     * 旧拼接至少两段且与字段重算结果完全一致才清空。只写 remark 列，不动金额 / 余额 / 时间。
     *
     * @return 实际清空的条数
     */
    internal suspend fun cleanLegacyAggregatedRemarks(): Int = withContext(Dispatchers.IO) {
        val rows = dao.getAutoTransactionsWithRemark()
        if (rows.isEmpty()) return@withContext 0
        var cleaned = 0
        dao.runInTransaction {
            for (tx in rows) {
                val channelLabel = LegacyRemarkCleaner.channelLabelOf(tx.source)
                if (LegacyRemarkCleaner.isLegacyAggregate(
                        tx.remark, tx.merchant, tx.productDesc, tx.payMethod, channelLabel
                    )
                ) {
                    cleaned += dao.clearTransactionRemark(tx.id)
                }
            }
        }
        if (cleaned > 0) notifyChanged()
        cleaned
    }

    companion object {
        private const val TAG = "FinanceManager"

        /** 旧版拼接说明清理的一次性标记（v1：2026-10-20「说明」语义变更） */
        private const val KEY_LEGACY_REMARK_CLEANED = "legacy_remark_cleaned_v1"

        /** 分类体系迁移的一次性标记（v1：旧扁平分类 → 一级/二级） */
        private const val KEY_CATEGORY_TREE = "category_tree_v1"

        /** 交易类型：支出 */
        const val TYPE_EXPENSE = 0

        /** 交易类型：收入 */
        const val TYPE_INCOME = 1

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
