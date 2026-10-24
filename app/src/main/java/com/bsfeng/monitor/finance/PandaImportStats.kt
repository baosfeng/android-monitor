package com.bsfeng.monitor.finance

// 来源：原 finance/PandaImporter.kt 的 ImportStats；职责：单次导入在事务内的计数（提交成功后才有意义）。

/** 单次导入的计数（仅在事务提交成功后才有意义） */
internal data class ImportStats(
    val totalTransactions: Int = 0,
    val importedTransactions: Int = 0,
    val skippedTransactions: Int = 0,
    val importedCategories: Int = 0,
    val importedAccounts: Int = 0,
    val importedBudgets: Int = 0,
) {
    /** 事务里真的写进了东西（全重复 / 全跳过时不必触发 UI 重载） */
    val hasWrites: Boolean
        get() = importedTransactions > 0 || importedCategories > 0 ||
            importedAccounts > 0 || importedBudgets > 0
}
