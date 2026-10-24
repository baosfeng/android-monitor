package com.bsfeng.monitor.finance

// 来源：原 finance/PandaImporter.kt；职责：熊猫记账导入入口（事务边界、去重计数、错误汇总），
// 各表读取见 PandaTableReader，单次计数见 ImportStats。

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 熊猫记账数据导入器。
 *
 * 读取熊猫记账导出的 SQLite 数据库文件，映射到本项目的记账数据模型。
 *
 * ## 映射规则
 * - t_bill → finance_transactions
 * - t_category → finance_categories（去重合并）
 * - t_card → finance_accounts（按名称去重）
 * - t_bbudget → finance_budgets
 * - 全部写入在**单个事务**内完成，任一步骤失败整体回滚
 * - 去重：同一 source_id 不重复导入
 */
class PandaImporter(private val financeManager: FinanceManager) {

    /** 导入结果 */
    data class ImportResult(
        val totalTransactions: Int = 0,
        val importedTransactions: Int = 0,
        val skippedTransactions: Int = 0,
        val importedCategories: Int = 0,
        val importedAccounts: Int = 0,
        val importedBudgets: Int = 0,
        val errors: List<String> = emptyList()
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val isSuccess: Boolean get() = importedTransactions > 0 && !hasErrors
    }

    private val reader = PandaTableReader()

    /**
     * 执行导入。
     *
     * @param pandaDbPath 熊猫记账数据库文件路径
     * @return 导入结果；失败时 [ImportResult.errors] 记录原因且所有计数为 0
     */
    suspend fun import(pandaDbPath: String): ImportResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        val pandaDb = try {
            SQLiteDatabase.openDatabase(pandaDbPath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return@withContext ImportResult(errors = listOf("无法打开数据库文件: ${e.message}"))
        }

        try {
            // 整个导入过程在同一个事务中执行：任一步骤抛异常即整体回滚，
            // 避免「分类已写入、账单未写入」这类中间态。
            val stats = financeManager.dao.runInTransaction { doImport(pandaDb, errors) }
            // 事务已提交：通知 UI 重新加载（导入的数据同样由上面那条刷新链路带出来）
            if (stats.hasWrites) financeManager.notifyChanged()
            ImportResult(
                totalTransactions = stats.totalTransactions,
                importedTransactions = stats.importedTransactions,
                skippedTransactions = stats.skippedTransactions,
                importedCategories = stats.importedCategories,
                importedAccounts = stats.importedAccounts,
                importedBudgets = stats.importedBudgets,
                errors = errors
            )
        } catch (e: Exception) {
            // 事务已回滚，所有计数归零，只保留错误原因
            ImportResult(errors = errors + "导入失败，已回滚: ${e.message}")
        } finally {
            pandaDb.close()
        }
    }

    /** 实际的读取与写入，运行在调用方开启的事务内 */
    private fun doImport(pandaDb: SQLiteDatabase, errors: MutableList<String>): ImportStats {
        // ---- 1. 分类（UNIQUE(name) 冲突自动忽略，只统计真正新增的） ----
        val categories = reader.readCategories(pandaDb)
        val categoriesInserted =
            if (categories.isEmpty()) 0 else financeManager.dao.insertCategories(categories)

        // ---- 2. 账户（按名称去重，只统计真正新增的） ----
        var accountsInserted = 0
        for (account in reader.readAccounts(pandaDb)) {
            if (financeManager.dao.getAccountByName(account.name) == null) {
                financeManager.dao.insertAccount(account)
                accountsInserted++
            }
        }

        // ---- 3. 预算 ----
        val budgets = reader.readBudgets(pandaDb)
        for (budget in budgets) {
            financeManager.dao.upsertBudget(budget)
        }

        // ---- 4. 账单（按 source + sourceId 去重） ----
        val allTransactions = reader.readTransactions(pandaDb, errors)
        var skipped = 0
        val newTransactions = allTransactions.filter { tx ->
            val exists = financeManager.dao.transactionExists(tx.source, tx.sourceId)
            if (exists) skipped++
            !exists
        }
        val imported = if (newTransactions.isEmpty()) 0
        else financeManager.dao.insertTransactions(newTransactions)

        return ImportStats(
            totalTransactions = allTransactions.size,
            importedTransactions = imported,
            skippedTransactions = skipped,
            importedCategories = categoriesInserted,
            importedAccounts = accountsInserted,
            importedBudgets = budgets.size,
        )
    }

    companion object {
        const val SOURCE_PANDA = "panda_import"

        /** 熊猫记账 t_bill.type：支出 */
        const val TYPE_EXPENSE = 0

        /** 熊猫记账 t_bill.type：收入 */
        const val TYPE_INCOME = 1

        /** 熊猫记账 t_bill.type：退款 */
        const val TYPE_REFUND = 3
    }
}
