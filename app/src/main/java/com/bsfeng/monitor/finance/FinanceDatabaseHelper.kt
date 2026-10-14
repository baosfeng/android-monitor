package com.bsfeng.monitor.finance

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 记账模块 SQLite 数据库帮助类。
 *
 * 独立于监控模块的 monitor.db，使用 finance.db。
 * 遵循项目规范：使用 SQLiteOpenHelper，不使用 Room。
 */
class FinanceDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        internal const val DATABASE_NAME = "finance.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var INSTANCE: FinanceDatabaseHelper? = null

        fun getInstance(context: Context): FinanceDatabaseHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FinanceDatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }

        /** 仅供测试：关闭并释放单例，使每个用例都能拿到干净的 finance.db */
        internal fun resetForTesting() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(TransactionEntity.CREATE_TABLE)
        db.execSQL(CategoryEntity.CREATE_TABLE)
        db.execSQL(AccountEntity.CREATE_TABLE)
        db.execSQL(BudgetEntity.CREATE_TABLE)
        db.execSQL(TagEntity.CREATE_TABLE)

        // 创建索引
        db.execSQL("CREATE INDEX idx_finance_tx_date ON ${TransactionEntity.TABLE_NAME}(${TransactionEntity.COL_DATE})")
        db.execSQL("CREATE INDEX idx_finance_tx_month ON ${TransactionEntity.TABLE_NAME}(${TransactionEntity.COL_DATE}, ${TransactionEntity.COL_TYPE})")
        db.execSQL("CREATE INDEX idx_finance_tx_source ON ${TransactionEntity.TABLE_NAME}(${TransactionEntity.COL_SOURCE})")
        db.execSQL("CREATE INDEX idx_finance_budget_month ON ${BudgetEntity.TABLE_NAME}(${BudgetEntity.COL_MONTH})")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // V1: 初始版本，无需迁移
    }
}
