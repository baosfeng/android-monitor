package com.bsfeng.monitor.finance

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 记账模块数据访问对象。
 *
 * 所有数据库读写操作集中在此类中，FinanceManager 通过此类访问数据。
 */
class FinanceDao(private val dbHelper: FinanceDatabaseHelper) {

    // ========== Transaction ==========

    /** 插入一条交易记录 */
    fun insertTransaction(tx: Transaction): Long {
        val db = dbHelper.writableDatabase
        return db.insertOrThrow(TransactionEntity.TABLE_NAME, null, TransactionEntity.toContentValues(tx))
    }

    /** 批量插入交易记录（事务） */
    fun insertTransactions(txs: List<Transaction>): Int {
        if (txs.isEmpty()) return 0
        val db = dbHelper.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (tx in txs) {
                val id = db.insertWithOnConflict(
                    TransactionEntity.TABLE_NAME, null,
                    TransactionEntity.toContentValues(tx),
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (id > 0) count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    /** 更新交易记录 */
    fun updateTransaction(tx: Transaction): Int {
        val db = dbHelper.writableDatabase
        val cv = TransactionEntity.toContentValues(tx)
        return db.update(TransactionEntity.TABLE_NAME, cv, "${TransactionEntity.COL_ID} = ?", arrayOf(tx.id.toString()))
    }

    /** 删除交易记录 */
    fun deleteTransaction(id: Long): Int {
        val db = dbHelper.writableDatabase
        return db.delete(TransactionEntity.TABLE_NAME, "${TransactionEntity.COL_ID} = ?", arrayOf(id.toString()))
    }

    /** 按 ID 查询 */
    fun getTransactionById(id: Long): Transaction? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_ID} = ?", arrayOf(id.toString()),
            null, null, null
        )
        return cursor.use { c ->
            if (c.moveToFirst()) TransactionEntity.fromCursor(c) else null
        }
    }

    /** 按月份查询（按日期降序） */
    fun getTransactionsByMonth(yearMonth: String): List<Transaction> {
        val db = dbHelper.readableDatabase
        val startDate = "$yearMonth-01"
        val endDate = getNextMonthStart(yearMonth)
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} < ?",
            arrayOf(startDate, endDate),
            null, null,
            "${TransactionEntity.COL_DATE} DESC, ${TransactionEntity.COL_DATE_TIME} DESC"
        )
        return TransactionEntity.listFromCursor(cursor)
    }

    /** 按日期范围查询 */
    fun getTransactionsByDateRange(startDate: String, endDate: String): List<Transaction> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} <= ?",
            arrayOf(startDate, endDate),
            null, null,
            "${TransactionEntity.COL_DATE} DESC, ${TransactionEntity.COL_DATE_TIME} DESC"
        )
        return TransactionEntity.listFromCursor(cursor)
    }

    /** 获取最近的交易记录 */
    fun getRecentTransactions(limit: Int = 50): List<Transaction> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            null, null, null, null,
            "${TransactionEntity.COL_DATE} DESC, ${TransactionEntity.COL_DATE_TIME} DESC",
            limit.toString()
        )
        return TransactionEntity.listFromCursor(cursor)
    }

    /**
     * 自动记账记录里按「来源 + 金额」找候选（交易详情页补全用）。
     *
     * 详情页发生在记账之后，无法用 sourceId 精确定位（结果页往往没有订单号/时间），
     * 因此先按来源（`auto_alipay` / `auto_wechat`）+ 金额捞一小批，再由纯逻辑按时间挑最近的一笔。
     */
    fun findAutoTransactionsByAmount(source: String, amount: Double, limit: Int = 20): List<Transaction> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_SOURCE} = ? AND ABS(${TransactionEntity.COL_AMOUNT} - ?) < 0.005",
            arrayOf(source, amount.toString()),
            null, null,
            "${TransactionEntity.COL_DATE_TIME} DESC",
            limit.toString()
        )
        return TransactionEntity.listFromCursor(cursor)
    }

    /**
     * 同一商户最近一笔交易（自动记账「上次选过的类型 / 分类」默认值用）。
     *
     * 精确匹配商户名：自动记账落库的商户来自同一页面，跨次稳定；手工录入的商户名与页面
     * 不一致时自然不命中 —— 宁可回落到解析器默认分类，也不能把别家店的分类套过来。
     */
    fun findLastByMerchant(merchant: String): Transaction? {
        if (merchant.isBlank()) return null
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_MERCHANT} = ?",
            arrayOf(merchant),
            null, null,
            "${TransactionEntity.COL_DATE_TIME} DESC, ${TransactionEntity.COL_ID} DESC",
            "1"
        )
        return TransactionEntity.listFromCursor(cursor).firstOrNull()
    }

    /** 搜索交易（按备注或分类名模糊匹配） */
    fun searchTransactions(query: String, limit: Int = 50): List<Transaction> {
        val db = dbHelper.readableDatabase
        val pattern = "%$query%"
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_REMARK} LIKE ? OR ${TransactionEntity.COL_CATEGORY_NAME} LIKE ?",
            arrayOf(pattern, pattern),
            null, null,
            "${TransactionEntity.COL_DATE} DESC",
            limit.toString()
        )
        return TransactionEntity.listFromCursor(cursor)
    }

    /** 获取某月的总支出 */
    fun getMonthlyExpenseTotal(yearMonth: String): Double {
        val db = dbHelper.readableDatabase
        val startDate = "$yearMonth-01"
        val endDate = getNextMonthStart(yearMonth)
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(${TransactionEntity.COL_AMOUNT}), 0) FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} < ? " +
                "AND ${TransactionEntity.COL_TYPE} = 0",
            arrayOf(startDate, endDate)
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    /** 获取某月的总收入 */
    fun getMonthlyIncomeTotal(yearMonth: String): Double {
        val db = dbHelper.readableDatabase
        val startDate = "$yearMonth-01"
        val endDate = getNextMonthStart(yearMonth)
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(${TransactionEntity.COL_AMOUNT}), 0) FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} < ? " +
                "AND ${TransactionEntity.COL_TYPE} = 1",
            arrayOf(startDate, endDate)
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    /** 获取某月的分类支出统计 */
    fun getMonthlyCategoryBreakdown(yearMonth: String, type: Int = 0): Map<String, Double> {
        val db = dbHelper.readableDatabase
        val startDate = "$yearMonth-01"
        val endDate = getNextMonthStart(yearMonth)
        val cursor = db.rawQuery(
            "SELECT ${TransactionEntity.COL_CATEGORY_NAME}, COALESCE(SUM(${TransactionEntity.COL_AMOUNT}), 0) " +
                "FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} < ? " +
                "AND ${TransactionEntity.COL_TYPE} = ? " +
                "GROUP BY ${TransactionEntity.COL_CATEGORY_NAME} " +
                "ORDER BY SUM(${TransactionEntity.COL_AMOUNT}) DESC",
            arrayOf(startDate, endDate, type.toString())
        )
        val map = mutableMapOf<String, Double>()
        cursor.use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getDouble(1)
            }
        }
        return map
    }

    /** 获取所有数据的月份列表（去重降序） */
    fun getAvailableMonths(): List<String> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT DISTINCT substr(${TransactionEntity.COL_DATE}, 1, 7) AS ym " +
                "FROM ${TransactionEntity.TABLE_NAME} ORDER BY ym DESC",
            null
        )
        val months = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) {
                months.add(c.getString(0))
            }
        }
        return months
    }

    /** 检查某笔交易是否已导入（按 source + sourceId 去重） */
    fun transactionExists(source: String, sourceId: String): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_SOURCE} = ? AND ${TransactionEntity.COL_SOURCE_ID} = ?",
            arrayOf(source, sourceId)
        )
        return cursor.use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    }

    // ========== Category ==========

    /**
     * 批量插入分类（去重，事务）。
     *
     * @return 实际新增的分类数量；已存在的同名分类（UNIQUE(name) 冲突）不计入
     */
    fun insertCategories(categories: List<Category>): Int {
        if (categories.isEmpty()) return 0
        val db = dbHelper.writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            for (cat in categories) {
                try {
                    db.insertOrThrow(CategoryEntity.TABLE_NAME, null, CategoryEntity.toContentValues(cat))
                    inserted++
                } catch (_: SQLiteConstraintException) {
                    // UNIQUE 冲突忽略
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** 获取所有分类 */
    fun getAllCategories(): List<Category> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CategoryEntity.TABLE_NAME, null, null, null, null, null,
            "${CategoryEntity.COL_TYPE} ASC, ${CategoryEntity.COL_SORT_ORDER} ASC"
        )
        return CategoryEntity.listFromCursor(cursor)
    }

    /** 按类型获取分类 */
    fun getCategoriesByType(type: Int): List<Category> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CategoryEntity.TABLE_NAME, null,
            "${CategoryEntity.COL_TYPE} = ?", arrayOf(type.toString()),
            null, null, "${CategoryEntity.COL_SORT_ORDER} ASC"
        )
        return CategoryEntity.listFromCursor(cursor)
    }

    // ========== Account ==========

    /** 插入账户（去重） */
    fun insertAccount(account: Account): Long {
        val db = dbHelper.writableDatabase
        return try {
            db.insertOrThrow(AccountEntity.TABLE_NAME, null, AccountEntity.toContentValues(account))
        } catch (_: SQLiteConstraintException) {
            // UNIQUE(name) 冲突时查询已有 ID
            getAccountByName(account.name)?.id ?: -1L
        }
    }

    /** 获取所有账户 */
    fun getAllAccounts(): List<Account> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            AccountEntity.TABLE_NAME, null, null, null, null, null,
            "${AccountEntity.COL_SORT_ORDER} ASC"
        )
        return AccountEntity.listFromCursor(cursor)
    }

    /** 按名称查询账户 */
    fun getAccountByName(name: String): Account? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            AccountEntity.TABLE_NAME, null,
            "${AccountEntity.COL_NAME} = ?", arrayOf(name),
            null, null, null
        )
        return cursor.use { c ->
            if (c.moveToFirst()) AccountEntity.fromCursor(c) else null
        }
    }

    /** 更新账户余额 */
    fun updateAccountBalance(accountId: Long, newBalance: Double): Int {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply { put(AccountEntity.COL_BALANCE, newBalance) }
        return db.update(AccountEntity.TABLE_NAME, cv, "${AccountEntity.COL_ID} = ?", arrayOf(accountId.toString()))
    }

    // ========== Budget ==========

    /** 插入或更新预算（按 month + category 唯一约束） */
    fun upsertBudget(budget: Budget): Long {
        val db = dbHelper.writableDatabase
        val cv = BudgetEntity.toContentValues(budget)
        return db.insertWithOnConflict(
            BudgetEntity.TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 获取某月预算列表 */
    fun getBudgetsByMonth(month: String): List<Budget> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            BudgetEntity.TABLE_NAME, null,
            "${BudgetEntity.COL_MONTH} = ?", arrayOf(month),
            null, null, null
        )
        return BudgetEntity.listFromCursor(cursor)
    }

    // ========== Helper ==========

    /**
     * 在单个数据库事务中执行 [block]，块内抛出异常时自动回滚。
     *
     * 供批量导入等需要「全部成功或全部回滚」的场景使用；
     * 与块内各方法自己开启的事务可安全嵌套（仅最外层提交生效）。
     */
    fun <T> runInTransaction(block: () -> T): T {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** 获取下个月起始日期字符串 yyyy-MM-dd */
    private fun getNextMonthStart(yearMonth: String): String {
        val parts = yearMonth.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        return if (month == 12) "${year + 1}-01-01"
        else String.format(java.util.Locale.CHINA, "%04d-%02d-01", year, month + 1)
    }
}
