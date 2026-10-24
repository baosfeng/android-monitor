package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt 的 Budget 分区；职责：预算表按「月份 + 分类」upsert 与按月份查询。

import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.finance.model.Budget

/** 预算数据访问（由 [FinanceDao] 持有并转发） */
internal class FinanceBudgetDao(private val dbHelper: FinanceDatabaseHelper) {

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
}
