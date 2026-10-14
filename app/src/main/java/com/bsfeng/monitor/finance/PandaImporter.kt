package com.bsfeng.monitor.finance

import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
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

    /** 单次导入的计数（仅在事务提交成功后才有意义） */
    private data class ImportStats(
        val totalTransactions: Int = 0,
        val importedTransactions: Int = 0,
        val skippedTransactions: Int = 0,
        val importedCategories: Int = 0,
        val importedAccounts: Int = 0,
        val importedBudgets: Int = 0,
    )

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
        val categories = readCategories(pandaDb)
        val categoriesInserted =
            if (categories.isEmpty()) 0 else financeManager.dao.insertCategories(categories)

        // ---- 2. 账户（按名称去重，只统计真正新增的） ----
        var accountsInserted = 0
        for (account in readAccounts(pandaDb)) {
            if (financeManager.dao.getAccountByName(account.name) == null) {
                financeManager.dao.insertAccount(account)
                accountsInserted++
            }
        }

        // ---- 3. 预算 ----
        val budgets = readBudgets(pandaDb)
        for (budget in budgets) {
            financeManager.dao.upsertBudget(budget)
        }

        // ---- 4. 账单（按 source + sourceId 去重） ----
        val allTransactions = readTransactions(pandaDb, errors)
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

    // ========== 熊猫数据库读取方法 ==========

    /**
     * 读取分类数据。
     * t_category.type: 1=收入, 0=支出
     */
    private fun readCategories(db: SQLiteDatabase): List<Category> {
        val cats = mutableListOf<Category>()
        val cursor = db.rawQuery(
            "SELECT t_id, category_name, type, icon_normal, color, t_pid, weight FROM t_category ORDER BY type, weight",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val type = c.getInt(2)
                val icon = c.getString(3) ?: ""
                val color = c.getString(4) ?: ""
                val parentId = c.getLong(5)
                val weight = c.getInt(6)

                cats.add(
                    Category(
                        name = name,
                        type = type,
                        iconName = icon,
                        color = color,
                        parentId = if (parentId < 0) -1 else parentId,
                        sortOrder = weight
                    )
                )
            }
        }
        return cats
    }

    /**
     * 读取账户数据。
     * t_card.type: 0=现金, 1=银行卡, 2=信用卡, 3=第三方支付
     */
    private fun readAccounts(db: SQLiteDatabase): List<Account> {
        val accounts = mutableListOf<Account>()
        val cursor = db.rawQuery(
            "SELECT id, name, type, money, weight FROM t_card ORDER BY weight",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val cardType = c.getInt(2)
                val balance = c.getDouble(3)
                val weight = c.getInt(4)

                // 映射卡片类型
                val mappedType = when (cardType) {
                    Account.TYPE_CASH -> Account.TYPE_CASH
                    Account.TYPE_BANK_CARD, 2 -> Account.TYPE_BANK_CARD
                    Account.TYPE_THIRD_PARTY -> Account.TYPE_THIRD_PARTY
                    else -> Account.TYPE_THIRD_PARTY
                }

                accounts.add(
                    Account(
                        name = name,
                        type = mappedType,
                        balance = balance,
                        sortOrder = weight
                    )
                )
            }
        }
        return accounts
    }

    /**
     * 读取预算数据。
     * t_bbudget.budget = 预算金额, t_bbudget.pay = 已支出, t_bbudget.month = yyyy-MM
     */
    private fun readBudgets(db: SQLiteDatabase): List<Budget> {
        val budgets = mutableListOf<Budget>()
        val cursor = db.rawQuery(
            "SELECT t_id, month, budget, pay FROM t_bbudget WHERE budget > 0",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val month = c.getString(1) ?: continue
                val budgetAmount = c.getDouble(2)
                val spentAmount = c.getDouble(3)

                budgets.add(
                    Budget(
                        month = month,
                        categoryName = "", // 总预算
                        budgetAmount = budgetAmount,
                        spentAmount = spentAmount
                    )
                )
            }
        }
        return budgets
    }

    /**
     * 读取账单记录。
     *
     * t_bill.type: 0=支出, 1=收入, 3=退款
     * 退款映射为「支出 + 负金额」，用于**抵扣**当月支出，而非虚增支出。
     *
     * 单行解析失败不静默吞掉：原因写入 [errors] 并随导入结果展示给用户。
     */
    private fun readTransactions(db: SQLiteDatabase, errors: MutableList<String>): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val cursor = db.rawQuery(
            """SELECT id, category, type, money, remark, card_name, tag, date, date_time, 
               createTime, updateTime, pics FROM t_bill ORDER BY date DESC, date_time DESC""",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                try {
                    val id = c.getLong(0)
                    val category = c.getString(1) ?: "其他"
                    val rawType = c.getInt(2)
                    val money = c.getDouble(3)
                    val remark = c.getString(4) ?: ""
                    val cardName = c.getString(5) ?: ""
                    val tag = c.getString(6) ?: ""
                    val date = c.getString(7) ?: ""
                    val dateTime = c.getString(8) ?: date
                    val createTime = c.getLong(9)
                    val updateTime = c.getLong(10)

                    // 映射交易类型：0→支出, 1→收入, 3→退款（支出 + 负金额）
                    val (mappedType, mappedAmount) = when (rawType) {
                        TYPE_EXPENSE -> TYPE_EXPENSE to money
                        TYPE_INCOME -> TYPE_INCOME to money
                        TYPE_REFUND -> TYPE_EXPENSE to -money
                        else -> TYPE_EXPENSE to money
                    }

                    transactions.add(
                        Transaction(
                            type = mappedType,
                            categoryName = category,
                            amount = mappedAmount,
                            accountName = cardName,
                            remark = remark,
                            tag = tag,
                            date = date,
                            dateTime = dateTime,
                            createdAt = createTime,
                            updatedAt = updateTime,
                            source = SOURCE_PANDA,
                            sourceId = id.toString()
                        )
                    )
                } catch (e: Exception) {
                    errors.add("第 ${c.position + 1} 行账单记录解析失败: ${e.message}")
                }
            }
        }
        return transactions
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
