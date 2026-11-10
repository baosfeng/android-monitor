package com.bsfeng.monitor.totp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 验证器 SQLite 数据库帮助类 —— 独立 totp.db，不混进 monitor.db / finance.db。
 *
 * 口径：
 * - 单例（双重校验锁），测试用 resetForTesting 释放；
 * - onCreate 建全量表 + 索引；
 * - onUpgrade 按**列存在性**幂等补列（PRAGMA table_info 先查再 ADD COLUMN），
 *   不靠重跑 CREATE TABLE 蒙混：CREATE TABLE IF NOT EXISTS 遇到已存在的旧表是空操作，
 *   旧表永远拿不到新列；
 * - 迁移失败**不吞异常**：SQLiteOpenHelper 把 onUpgrade 包在事务里，抛出即回滚，App 直接报错；
 * - onDowngrade 故意留空（用户回装旧版 APK 时保留数据，读取按列名不受多余列影响）。
 */
class TotpDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 不要 try/catch 吞异常：迁移失败必须让 App 起不来，而不是带着半套表继续跑。
        if (oldVersion < DATABASE_VERSION) {
            migrate(db, oldVersion)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 故意留空：库版本比代码新时保留数据，不做任何结构变更
    }

    /** 建表 + 索引；幂等，onCreate 与「表缺失」的迁移路径共用。 */
    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(TotpEntity.CREATE_TABLE)
        db.execSQL(TotpEntity.CREATE_INDEX)
    }

    /**
     * 按 oldVersion 逐级补结构。
     *
     * 表不存在（数据库文件存在但表被删过 / 早期手工建的库）→ 直接全量建表，一步到位；
     * 表存在但版本低 → 只做 ADD COLUMN，绝不重建（重建会丢数据）。
     */
    private fun migrate(db: SQLiteDatabase, oldVersion: Int) {
        if (!tableExists(db)) {
            createSchema(db)
            return
        }
        if (oldVersion < 2) {
            addColumnIfMissing(db, TotpEntity.COL_OTP_TYPE, TotpEntity.ADD_COLUMN_OTP_TYPE)
            addColumnIfMissing(db, TotpEntity.COL_COUNTER, TotpEntity.ADD_COLUMN_COUNTER)
        }
        db.execSQL(TotpEntity.CREATE_INDEX)
    }

    /** 列不存在才 ADD；ADD 后立刻回读自证，避免「execSQL 没报错但列没落」的静默失败。 */
    private fun addColumnIfMissing(db: SQLiteDatabase, column: String, ddl: String) {
        if (column in tableColumns(db)) return
        db.execSQL(ddl)
        check(column in tableColumns(db)) { "迁移失败：" + column + " 列未创建" }
    }

    /** 表是否存在（sqlite_master 查 type=table）。 */
    private fun tableExists(db: SQLiteDatabase): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(TotpEntity.TABLE_NAME),
        ).use { cursor -> cursor.moveToFirst() }

    companion object {
        internal const val DATABASE_NAME = "totp.db"

        /**
         * V2：账户表新增 otp_type（TOTP / HOTP）与 counter（HOTP 计数器）。
         * 密钥仍不在这里，见 TotpSecretStore。
         */
        internal const val DATABASE_VERSION = 2

        @Volatile
        private var INSTANCE: TotpDatabaseHelper? = null

        fun getInstance(context: Context): TotpDatabaseHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TotpDatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }

        /** 仅供测试：关闭并释放单例，使每个用例都能拿到干净的 totp.db。 */
        internal fun resetForTesting() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /** 当前表的列名集合（迁移测试与幂等判定共用）。表不存在返回空集。 */
        internal fun tableColumns(db: SQLiteDatabase): Set<String> =
            db.rawQuery("PRAGMA table_info(" + TotpEntity.TABLE_NAME + ")", null).use { cursor ->
                val names = mutableSetOf<String>()
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) names.add(cursor.getString(nameIndex))
                names
            }
    }
}
