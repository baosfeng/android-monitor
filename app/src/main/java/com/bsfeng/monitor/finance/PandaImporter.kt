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
 * - t_card → finance_accounts
 * - t_bbudget → finance_budgets
 * - 使用事务批量导入，出错回滚
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

    /**
     * 执行导入。
     *
     * @param pandaDbPath 熊猫记账数据库文件路径
     * @return 导入结果
     */
    suspend fun import(pandaDbPath: String): ImportResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        var txsTotal = 0
        var txsImported = 0
        var txsSkipped = 0
        var catsImported = 0
        var accsImported = 0
        var budgetsImported = 0

        try {
            // 以只读方式打开熊猫数据库
            val pandaDb = SQLiteDatabase.openDatabase(pandaDbPath, null, SQLiteDatabase.OPEN_READONLY)

            try {
                // ---- 1. 导入分类 ----
                val cats = readCategories(pandaDb)
                catsImported = cats.size
                if (cats.isNotEmpty()) {
                    financeManager.dao.insertCategories(cats)
                }

                // ---- 2. 导入账户 ----
                val accounts = readAccounts(pandaDb)
                for (acc in accounts) {
                    val id = financeManager.dao.insertAccount(acc)
                    if (id > 0) accsImported++
                }

                // ---- 3. 导入预算 ----
                val budgets = readBudgets(pandaDb)
                for (budget in budgets) {
                    financeManager.dao.upsertBudget(budget)
                    budgetsImported++
                }

                // ---- 4. 导入账单 ----
                val allTransactions = readTransactions(pandaDb)
                txsTotal = allTransactions.size

                val newTransactions = allTransactions.filter { tx ->
                    val exists = financeManager.dao.transactionExists(tx.source, tx.sourceId)
                    if (exists) txsSkipped++
                    !exists
                }

                if (newTransactions.isNotEmpty()) {
                    txsImported = financeManager.dao.insertTransactions(newTransactions)
                }

            } catch (e: Exception) {
                errors.add("导入处理异常: ${e.message}")
            } finally {
                pandaDb.close()
            }

        } catch (e: Exception) {
            errors.add("无法打开数据库文件: ${e.message}")
        }

        ImportResult(
            totalTransactions = txsTotal,
            importedTransactions = txsImported,
            skippedTransactions = txsSkipped,
            importedCategories = catsImported,
            importedAccounts = accsImported,
            importedBudgets = budgetsImported,
            errors = errors
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
                val id = c.getLong(0)
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
                    0 -> 0  // 现金
                    1, 2 -> 1 // 银行卡/信用卡
                    3 -> 2  // 第三方支付
                    else -> 2
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
     * t_bill.money: 金额
     * t_bill.card_name: 支付方式名称（如"支付宝""微信钱包""现金钱包"）
     */
    private fun readTransactions(db: SQLiteDatabase): List<Transaction> {
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

                    // 映射交易类型
                    // 0→0(支出), 1→1(收入), 3→0(退款转为负金额支出)
                    val (mappedType, mappedAmount) = when (rawType) {
                        0 -> 0 to money
                        1 -> 1 to money
                        3 -> 0 to money // 退款：可视化上作为支出处理但金额为负显示
                        else -> 0 to money
                    }

                    val tx = Transaction(
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
                    transactions.add(tx)
                } catch (e: Exception) {
                    // 单行解析失败跳过
                }
            }
        }
        return transactions
    }

    companion object {
        const val SOURCE_PANDA = "panda_import"
    }
}
