package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt 的 Account 分区；职责：账户表读写与余额更新。

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import com.bsfeng.monitor.finance.model.Account

/** 账户数据访问（由 [FinanceDao] 持有并转发） */
internal class FinanceAccountDao(private val dbHelper: FinanceDatabaseHelper) {

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
}
