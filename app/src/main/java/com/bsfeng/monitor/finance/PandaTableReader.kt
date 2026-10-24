package com.bsfeng.monitor.finance

// 来源：原 finance/PandaImporter.kt 的读取方法；职责：读取熊猫记账 SQLite 的四张表（分类/账户/预算/账单）并映射到本 App 模型。

import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 熊猫记账数据库读取（只读，不写库）。
 *
 * 映射约定见 [PandaImporter] 的文件头说明；所有查询文本与列序与原实现保持一致。
 */
internal class PandaTableReader {

    /**
     * 读取分类数据。
     * t_category.type: 1=收入, 0=支出
     */
    fun readCategories(db: SQLiteDatabase): List<Category> {
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
    fun readAccounts(db: SQLiteDatabase): List<Account> {
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
    fun readBudgets(db: SQLiteDatabase): List<Budget> {
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
    fun readTransactions(db: SQLiteDatabase, errors: MutableList<String>): List<Transaction> {
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
                        PandaImporter.TYPE_EXPENSE -> PandaImporter.TYPE_EXPENSE to money
                        PandaImporter.TYPE_INCOME -> PandaImporter.TYPE_INCOME to money
                        PandaImporter.TYPE_REFUND -> PandaImporter.TYPE_EXPENSE to -money
                        else -> PandaImporter.TYPE_EXPENSE to money
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
                            source = PandaImporter.SOURCE_PANDA,
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
}
