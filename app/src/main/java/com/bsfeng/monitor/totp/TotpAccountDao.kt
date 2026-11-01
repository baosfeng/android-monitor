package com.bsfeng.monitor.totp

import android.database.sqlite.SQLiteConstraintException

/**
 * TOTP 账户表数据访问 —— 写用 writableDatabase、读用 readableDatabase。
 *
 * 密钥不在本表：插入拿到 id 后由 Repository 写 TotpSecretStore。
 */
class TotpAccountDao(private val dbHelper: TotpDatabaseHelper) {

    /** 按 sort_order 升序取全部账户。 */
    fun listAll(): List<TotpAccountRow> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TotpEntity.TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            TotpEntity.COL_SORT_ORDER + " ASC, " + TotpEntity.COL_ID + " ASC",
        )
        return TotpEntity.listFromCursor(cursor)
    }

    /** 按 id 取单个账户。 */
    fun getById(id: Long): TotpAccountRow? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TotpEntity.TABLE_NAME,
            null,
            TotpEntity.COL_ID + " = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        )
        return cursor.use { c -> if (c.moveToFirst()) TotpEntity.fromCursor(c) else null }
    }

    /** 账户总数（空态判断）。 */
    fun count(): Int {
        val db = dbHelper.readableDatabase
        return db.rawQuery("SELECT COUNT(*) FROM " + TotpEntity.TABLE_NAME, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 下一个排序位（末尾追加）。 */
    fun nextSortOrder(): Int {
        val db = dbHelper.readableDatabase
        return db.rawQuery(
            "SELECT COALESCE(MAX(" + TotpEntity.COL_SORT_ORDER + "), -1) FROM " + TotpEntity.TABLE_NAME,
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) + 1 else 0 }
    }

    /** 插入；返回自增 id（失败抛 SQLiteException，不返回 -1 假装成功）。 */
    fun insert(row: TotpAccountRow): Long = try {
        dbHelper.writableDatabase.insertOrThrow(
            TotpEntity.TABLE_NAME,
            null,
            TotpEntity.toContentValues(row),
        )
    } catch (e: SQLiteConstraintException) {
        throw IllegalStateException("账户写入冲突", e)
    }

    /** 按 id 更新；返回受影响行数（0 = 不存在）。 */
    fun update(row: TotpAccountRow): Int = dbHelper.writableDatabase.update(
        TotpEntity.TABLE_NAME,
        TotpEntity.toContentValues(row),
        TotpEntity.COL_ID + " = ?",
        arrayOf(row.id.toString()),
    )

    /** 按 id 删除；返回受影响行数。 */
    fun delete(id: Long): Int = dbHelper.writableDatabase.delete(
        TotpEntity.TABLE_NAME,
        TotpEntity.COL_ID + " = ?",
        arrayOf(id.toString()),
    )

    /**
     * 按给定顺序重写 sort_order（[orderedIds] 下标即新顺序）。
     *
     * 显式事务，一条失败整批回滚；不在事务里再调 beginTransaction（不嵌套）。
     */
    fun reorder(orderedIds: List<Long>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                val values = android.content.ContentValues().apply {
                    put(TotpEntity.COL_SORT_ORDER, index)
                }
                db.update(
                    TotpEntity.TABLE_NAME,
                    values,
                    TotpEntity.COL_ID + " = ?",
                    arrayOf(id.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
