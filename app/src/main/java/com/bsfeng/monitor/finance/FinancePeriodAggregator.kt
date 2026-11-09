package com.bsfeng.monitor.finance

// 来源：记账「时间单位查询」需求（原型 s04）；职责：五档周期的 SQL 聚合 —— 三值汇总、笔数、分桶序列。
// 金额一律走 SQL COALESCE(SUM(...), 0)，不捞列表回内存累加（月度金额会静默偏低，见 FinanceTransactionDao.sumByDateRange 注释）。

import com.bsfeng.monitor.finance.model.PeriodBucket

/**
 * 周期聚合查询（被 [FinanceTransactionDao] 与 [FinancePeriodService] 共用）。
 *
 * 区间一律「含两端」的 yyyy-MM-dd 文本；分桶键表达式统一来自 [FinancePeriodRange.bucketKeyExpr]，
 * 保证 SQL 分桶与 [FinancePeriodRange.labelsOf] 的标签一一对应。
 */
internal object FinancePeriodAggregator {

    /** 区间内交易笔数（支出口径的笔数 = 支出 + 退款行，与列表条数一致）。 */
    fun countByPeriod(db: android.database.sqlite.SQLiteDatabase, fromDate: String, toDate: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} <= ?",
            arrayOf(fromDate, toDate)
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * 分桶序列：按 [FinancePeriodRange.bucketKeyExpr] 聚合成「键 → 桶」，再按标签铺满定长序列。
     *
     * 桶内 count 用 COUNT(*)，expense / income 用条件 SUM（CASE WHEN），一次扫描同时得出三列。
     */
    fun bucketsByPeriod(
        db: android.database.sqlite.SQLiteDatabase,
        period: FinancePeriod,
        fromDate: String,
        toDate: String,
    ): List<PeriodBucket> {
        val keyExpr = FinancePeriodRange.bucketKeyExpr(period)
        val cursor = db.rawQuery(
            "SELECT $keyExpr AS k, " +
                "COUNT(*) AS cnt, " +
                "COALESCE(SUM(CASE WHEN ${TransactionEntity.COL_TYPE} = 0 " +
                    "THEN ${TransactionEntity.COL_AMOUNT} ELSE 0 END), 0) AS exp, " +
                "COALESCE(SUM(CASE WHEN ${TransactionEntity.COL_TYPE} = 1 " +
                    "THEN ${TransactionEntity.COL_AMOUNT} ELSE 0 END), 0) AS inc " +
                "FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_DATE} >= ? AND ${TransactionEntity.COL_DATE} <= ? " +
                "GROUP BY k",
            arrayOf(fromDate, toDate)
        )
        val rows = mutableMapOf<String, PeriodBucket>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(0) ?: continue
                val label = FinancePeriodRange.labelOfKey(period, raw) ?: continue
                rows[label] = PeriodBucket(
                    label = label,
                    expense = c.getDouble(2),
                    income = c.getDouble(3),
                    count = c.getInt(1),
                )
            }
        }
        val labels = FinancePeriodRange.labelsOf(period, fromDate, toDate)
        return FinancePeriodRange.fill(labels, rows)
    }

    /** 库内最早一笔的交易日期（all 档区间起点）；空库返回 null。 */
    fun earliestDate(db: android.database.sqlite.SQLiteDatabase): String? {
        val cursor = db.rawQuery(
            "SELECT MIN(${TransactionEntity.COL_DATE}) FROM ${TransactionEntity.TABLE_NAME} " +
                "WHERE ${TransactionEntity.COL_DATE} != ''",
            null
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }
}
