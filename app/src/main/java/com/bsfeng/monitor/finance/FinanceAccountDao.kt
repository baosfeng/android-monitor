package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt 的 Account 分区；职责：账户表读写与余额更新。

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

    /**
     * 余额按相对增量原子更新：`balance = balance + delta`。
     *
     * 为什么不是「读整行 → 内存相加 → 整行写回」：自动记账（AutoBookPersistence 在 IO scope）与
     * 手动记账（记账页协程）会并发写同一账户，两个写者各自读到同一个旧值时，后写者会把前者的
     * 增量覆盖掉 —— 账目总额与账户余额从此永久偏差，且没有任何报错可以察觉。
     * 把加法交给 SQLite 一条 UPDATE：写语句串行执行，后到的那条读到的是前者已提交的值。
     *
     * @return 受影响行数；0 表示账户不存在（或账户名为空），调用方按「跳过」处理，
     *         不让差额落到别的账户上
     */
    fun addToAccountBalance(accountName: String, delta: Double): Int {
        if (accountName.isBlank() || delta == 0.0) return 0
        val db = dbHelper.writableDatabase
        val statement = db.compileStatement(
            "UPDATE ${AccountEntity.TABLE_NAME} " +
                "SET ${AccountEntity.COL_BALANCE} = ${AccountEntity.COL_BALANCE} + ? " +
                "WHERE ${AccountEntity.COL_NAME} = ?"
        )
        return statement.use { st ->
            st.bindDouble(1, delta)
            st.bindString(2, accountName)
            st.executeUpdateDelete()
        }
    }
}
