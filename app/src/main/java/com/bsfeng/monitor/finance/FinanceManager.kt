package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceManager.kt；职责：记账业务统一入口（协程 API + 单例 + 变更信号）。
// 余额副作用见 FinanceAccountBalance，统计见 FinanceStatsService，种子/迁移见 FinanceCategorySeeder，
// 预算见 FinanceBudgetService，旧版说明清理见 FinanceLegacyCleanup。

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

    private val accountBalance = FinanceAccountBalance(dao)
    private val statsService = FinanceStatsService(dao)
    private val categorySeeder = FinanceCategorySeeder(dao)
    private val budgetService = FinanceBudgetService(dao)
    private val legacyCleanup = FinanceLegacyCleanup(dao)

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
        val id = insertTransactionWithEffect(tx)
        notifyChanged()
        id
    }

    /**
     * 「写交易 + 余额副作用」的最小原子单元，**同步阻塞**、调用方拥有事务边界。
     *
     * 为什么单独抽出这一层：账单导入要「整批一个事务」（[BillImporter]），逐笔调
     * [addTransaction] 会让每笔各提交一次事务，且必须绕开余额副作用才能留在批事务里 ——
     * 之前正是因为绕开了它，导入的每一笔都不进账户余额。现在两条入口共用这一条路径：
     * 导入在外层事务里逐笔调它，手动/自动记账则由 [addTransaction] 包一层事务。
     *
     * 交易写库与余额变更必须在同一个事务里：只写成交易、余额没跟上，或反过来，
     * 都会让「账目总额」与「账户余额」永久偏差，而界面上看不出异常。
     */
    internal fun insertTransactionWithEffect(tx: Transaction): Long = dao.runInTransaction {
        val id = dao.insertTransaction(tx)
        accountBalance.applyTransactionEffect(tx, sign = 1.0)
        id
    }

    suspend fun deleteTransaction(id: Long): Unit = withContext(Dispatchers.IO) {
        dao.runInTransaction {
            val existing = dao.getTransactionById(id)
            dao.deleteTransaction(id)
            if (existing != null) accountBalance.applyTransactionEffect(existing, sign = -1.0)
        }
        notifyChanged()
    }

    suspend fun updateTransaction(tx: Transaction): Unit = withContext(Dispatchers.IO) {
        dao.runInTransaction {
            val old = dao.getTransactionById(tx.id)
            dao.updateTransaction(tx)
            // 先按旧值回冲、再按新值计入：换账户 / 改金额 / 支出↔转账互转都由这一条路径覆盖
            if (old != null) accountBalance.applyTransactionEffect(old, sign = -1.0)
            accountBalance.applyTransactionEffect(tx, sign = 1.0)
        }
        notifyChanged()
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

    /**
     * 日期区间（含两端，`yyyy-MM-dd`）的金额汇总，走 SQL `SUM`。
     *
     * 统计类口径一律走这里：捞列表再在内存里 sum 会被「最近 N 笔」的截断悄悄改小。
     */
    suspend fun sumByDateRange(fromDate: String, toDate: String, expense: Boolean): Double =
        withContext(Dispatchers.IO) {
            dao.sumByDateRange(fromDate, toDate, if (expense) TYPE_EXPENSE else TYPE_INCOME)
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
        statsService.monthlyStats(yearMonth)
    }

    suspend fun getCategoryBreakdown(yearMonth: String, type: Int = 0): Map<String, Double> = withContext(Dispatchers.IO) {
        statsService.categoryBreakdown(yearMonth, type)
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
        categorySeeder.seedDefaultCategories()
    }

    /**
     * 分类目录补齐（每个 [CategoryCatalog.VERSION] 只跑一次）。
     *
     * 已安装用户的 finance.db 里已有旧目录，[seedDefaultCategories] 只认「表为空」，
     * 所以 [CategoryCatalog] 扩容后必须靠这里把新分类补进去，否则真机上永远看不到新分类。
     * 标记按版本号命名：下次再扩容只递增 [CategoryCatalog.VERSION] 即可再补一次。
     */
    suspend fun syncCatalogOnce() {
        val key = KEY_CATALOG_SYNC_PREFIX + CategoryCatalog.VERSION
        if (prefs.getBoolean(key, false)) return
        val added = syncCatalog()
        prefs.edit { putBoolean(key, true) }
        if (added > 0) {
            android.util.Log.i(TAG, "分类目录已补齐 $added 条（目录版本 ${CategoryCatalog.VERSION}）")
        }
    }

    /**
     * 补齐分类目录（幂等，可重复调用；单测直接调它）。
     *
     * @return 实际新增条数；再跑一次返回 0
     */
    internal suspend fun syncCatalog(): Int = withContext(Dispatchers.IO) {
        val added = categorySeeder.syncCatalog()
        if (added > 0) notifyChanged()
        added
    }

    /**
     * 分类体系一次性迁移（旧扁平 → 新一级/二级）。
     *
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
        val updated = categorySeeder.migrateCategoryTree()
        if (updated > 0) notifyChanged()
        updated
    }

    suspend fun seedDefaultAccounts() = withContext(Dispatchers.IO) {
        categorySeeder.seedDefaultAccounts()
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
        budgetService.monthlyBudget(yearMonth, TYPE_EXPENSE)
    }

    suspend fun setBudget(month: String, categoryName: String, amount: Double): Unit = withContext(Dispatchers.IO) {
        budgetService.setBudget(month, categoryName, amount)
        notifyChanged()
    }

    // ========== 数据完整性 ==========

    /** 初始化：分类体系迁移（只跑一次）→ 种子兜底 → 目录补齐 → 其他一次性清理 */
    suspend fun ensureInitialized() {
        migrateCategoryTreeOnce()
        seedDefaultCategories()
        syncCatalogOnce()
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
        val cleaned = legacyCleanup.cleanLegacyAggregatedRemarks()
        if (cleaned > 0) notifyChanged()
        cleaned
    }

    companion object {
        private const val TAG = "FinanceManager"

        /** 旧版拼接说明清理的一次性标记（v1：2026-10-20「说明」语义变更） */
        private const val KEY_LEGACY_REMARK_CLEANED = "legacy_remark_cleaned_v1"

        /** 分类体系迁移的一次性标记（v1：旧扁平分类 → 一级/二级） */
        private const val KEY_CATEGORY_TREE = "category_tree_v1"

        /** 分类目录补齐标记前缀，拼 [CategoryCatalog.VERSION] 组成 key（每次扩容各补一次） */
        private const val KEY_CATALOG_SYNC_PREFIX = "catalog_sync_v"

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
                // .also 不能省：漏了它这个「单例」每次调用都新建一个 Manager，
                // 各自持有独立的 changeVersion，写事件的实例与订阅的实例对不上（组件不刷新）。
                INSTANCE ?: FinanceManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}