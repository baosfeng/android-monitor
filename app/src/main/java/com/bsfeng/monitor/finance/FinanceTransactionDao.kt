package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt 的 Transaction 分区；职责：交易记录的增删改查、月度聚合与去重判断。

import android.content.ContentValues
import androidx.core.database.sqlite.transaction
import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 交易记录数据访问（由 [FinanceDao] 持有并转发）。
 *
 * 金额一律按数据库原值读出，聚合在 SQL 里用 `COALESCE(SUM(...), 0)` 完成——
 * 拆分时保持原有查询文本与参数顺序不变，避免浮点累加顺序变化。
 */
internal class FinanceTransactionDao(private val dbHelper: FinanceDatabaseHelper) {

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
        db.transaction {
            for (tx in txs) {
                val id = insertWithOnConflict(
                    TransactionEntity.TABLE_NAME, null,
                    TransactionEntity.toContentValues(tx),
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (id > 0) count++
            }
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
        val endDate = FinanceDateRange.nextMonthStart(yearMonth)
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
     * 自动记账记录里「说明」非空的全部行（旧版拼接残留清理用，见 [LegacyRemarkCleaner]）。
     *
     * `LIKE 'auto_%'` 覆盖 auto_alipay / auto_wechat；手动记账（manual）与熊猫导入不参与。
     */
    fun getAutoTransactionsWithRemark(): List<Transaction> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TransactionEntity.TABLE_NAME, null,
            "${TransactionEntity.COL_SOURCE} LIKE 'auto\\_%' ESCAPE '\\' AND ${TransactionEntity.COL_REMARK} != ''",
            null, null, null, null
        )
        return TransactionEntity.listFromCursor(cursor)
    }

    /** 只清说明列（旧拼接迁移用）：不动类型 / 分类 / 金额 / 余额。 */
    fun clearTransactionRemark(id: Long): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(TransactionEntity.COL_REMARK, "") }
        return db.update(
            TransactionEntity.TABLE_NAME, values,
            "${TransactionEntity.COL_ID} = ?", arrayOf(id.toString())
        )
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

    /** 搜索交易（按说明或分类名模糊匹配） */
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
        val endDate = FinanceDateRange.nextMonthStart(yearMonth)
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
        val endDate = FinanceDateRange.nextMonthStart(yearMonth)
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
        val endDate = FinanceDateRange.nextMonthStart(yearMonth)
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

    /** 把交易里旧的分类名批量改到新分类（迁移用；不动金额/余额/时间） */
    fun updateTransactionCategory(oldName: String, newName: String): Int {
        if (oldName == newName) return 0
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(TransactionEntity.COL_CATEGORY_NAME, newName) }
        return db.update(
            TransactionEntity.TABLE_NAME, values,
            "${TransactionEntity.COL_CATEGORY_NAME} = ?", arrayOf(oldName)
        )
    }

    /** 交易里出现过的分类名 + 类型（迁移映射用；跳过空分类） */
    fun getDistinctTransactionCategoryTypes(): List<Pair<String, Int>> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT DISTINCT ${TransactionEntity.COL_CATEGORY_NAME}, ${TransactionEntity.COL_TYPE} " +
                "FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_CATEGORY_NAME} != ''",
            null
        )
        val rows = mutableListOf<Pair<String, Int>>()
        cursor.use { c ->
            while (c.moveToNext()) rows += c.getString(0) to c.getInt(1)
        }
        return rows
    }
}
