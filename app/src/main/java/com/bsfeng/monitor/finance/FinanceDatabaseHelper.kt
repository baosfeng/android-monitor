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

        /**
         * 数据库版本。
         *
         * - V1：初始版本（交易 + 分类 + 账户 + 预算 + 标签）
         * - V2：[TransactionEntity] 增 6 列，把原先只能挤进 remark 的结构化信息拆开
         *   （商户 / 商品说明 / 付款方式 / 订单号 / 平台分类 / 原始交易时间），便于统计与分析
         */
        internal const val DATABASE_VERSION = 2

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
        // V1 → V2：新增 6 列。全部 NOT NULL DEFAULT ''，老数据（手动录入 / 熊猫导入）自动为空串，
        // 不破坏任何既有行；ALTER TABLE ADD COLUMN 在 SQLite 里是元数据操作，代价可忽略。
        if (oldVersion < 2) {
            val t = TransactionEntity.TABLE_NAME
            db.execSQL("ALTER TABLE $t ADD COLUMN ${TransactionEntity.COL_MERCHANT} TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $t ADD COLUMN ${TransactionEntity.COL_PRODUCT_DESC} TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $t ADD COLUMN ${TransactionEntity.COL_PAY_METHOD} TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $t ADD COLUMN ${TransactionEntity.COL_ORDER_ID} TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $t ADD COLUMN ${TransactionEntity.COL_PLATFORM_CATEGORY} TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $t ADD COLUMN ${TransactionEntity.COL_TRADE_TIME} TEXT NOT NULL DEFAULT ''")
            // 按商户 / 付款方式做统计要分组，先建索引
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_finance_tx_merchant ON $t(${TransactionEntity.COL_MERCHANT})")
        }
    }

    /**
     * 允许降级。
     *
     * 默认实现会抛 `SQLiteDowngradeFailedException`（库版本比代码新）。但本项目侧载自用，
     * 用户完全可能装回旧版 APK —— 而读取一律按**列名**取值（[TransactionEntity.fromCursor]），
     * 多出来的列会被自然忽略，所以降级是安全的，不该让 App 直接起不来。
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 故意留空：保留数据，不做任何结构变更
    }
}
