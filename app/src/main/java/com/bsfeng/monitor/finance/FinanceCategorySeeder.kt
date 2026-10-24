package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceManager.kt 的种子数据与分类体系迁移；职责：默认分类/账户初始化、旧扁平分类迁移到一级/二级。

import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.CategoryCatalog

/**
 * 分类与账户的种子数据、分类体系一次性迁移。
 *
 * 由 [FinanceManager] 持有；迁移与种子都要求"已有数据则不覆盖"，
 * 顺序（先清空分类表 → 插新体系 → 交易/预算改名）与原实现一致。
 */
internal class FinanceCategorySeeder(private val dao: FinanceDao) {

    /** 插入一级 + 二级分类（调用方负责事务；一级先插拿到 id，二级挂 parentId） */
    fun insertCatalog() {
        for ((type, seeds) in listOf(0 to CategoryCatalog.expense, 1 to CategoryCatalog.income)) {
            seeds.forEachIndexed { index, seed ->
                val topId = dao.insertCategory(
                    Category(name = seed.name, type = type, parentId = -1, sortOrder = index)
                )
                if (topId > 0) {
                    seed.children.forEachIndexed { childIndex, child ->
                        dao.insertCategory(
                            Category(
                                name = child,
                                type = type,
                                parentId = topId,
                                sortOrder = childIndex,
                            )
                        )
                    }
                }
            }
        }
    }

    /** 分类表为空时写入默认分类 */
    fun seedDefaultCategories() {
        if (dao.getAllCategories().isNotEmpty()) return
        insertCatalog()
    }

    /** 账户表为空时写入默认账户 */
    fun seedDefaultAccounts() {
        val existing = dao.getAllAccounts()
        if (existing.isNotEmpty()) return

        for ((index, acc) in Account.defaultAccounts.withIndex()) {
            dao.insertAccount(acc.copy(sortOrder = index))
        }
    }

    /**
     * 执行分类体系迁移（幂等，可重复调用；单测直接调它）。
     *
     * 事务内：清空分类表 → 插入新体系 → 交易/预算按 [CategoryMigration] 归到一级。
     *
     * @return 改写的交易/预算行数
     */
    fun migrateCategoryTree(): Int = dao.runInTransaction {
        dao.clearCategories()
        insertCatalog()
        var count = 0
        for ((name, type) in dao.getDistinctTransactionCategoryTypes()) {
            val target = CategoryMigration.mapLegacy(name, type)
            count += dao.updateTransactionCategory(name, target)
        }
        // 预算只有支出分类（总预算 category_name 为空，查询已跳过）
        for (name in dao.getDistinctBudgetCategoryNames()) {
            count += dao.updateBudgetCategory(name, CategoryMigration.mapLegacy(name, 0))
        }
        count
    }
}
