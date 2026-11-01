package com.bsfeng.monitor.totp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 验证器 SQLite 数据库帮助类 —— 独立 totp.db，不混进 monitor.db / finance.db。
 *
 * 口径：
 * - 单例（双重校验锁），测试用 resetForTesting 释放；
 * - onCreate 建表 + 索引；onUpgrade 复用同一段带 IF NOT EXISTS 的 DDL，天然幂等；
 * - 迁移失败**不吞异常**：SQLiteOpenHelper 会把 onUpgrade 包在事务里，抛出即回滚，App 直接报错；
 * - onDowngrade 故意留空（用户回装旧版 APK 时保留数据，读取按列名不受多余列影响）。
 */
class TotpDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // V1 即最新结构；以后升版本在这里按 oldVersion 追加幂等 DDL（IF NOT EXISTS / 先查列再 ADD）。
        // 不要 try/catch 吞异常：迁移失败必须让 App 起不来，而不是带着半套表继续跑。
        if (oldVersion < DATABASE_VERSION) {
            createSchema(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 故意留空：库版本比代码新时保留数据，不做任何结构变更
    }

    /** 建表 + 索引；幂等，onCreate 与 onUpgrade 共用。 */
    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(TotpEntity.CREATE_TABLE)
        db.execSQL(TotpEntity.CREATE_INDEX)
    }

    companion object {
        internal const val DATABASE_NAME = "totp.db"

        /** V1：账户表（密钥不在这里，见 TotpSecretStore）。 */
        internal const val DATABASE_VERSION = 1

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
    }
}
