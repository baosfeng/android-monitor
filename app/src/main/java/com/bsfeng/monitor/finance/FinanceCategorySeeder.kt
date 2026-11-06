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

    /**
     * 把 [CategoryCatalog] 里**新增**的分类补进已有分类表（幂等，可重复调用）。
     *
     * 为什么需要它：[seedDefaultCategories] 只在分类表为空时写入，而 [CategoryCatalog] 扩容后
     * 存量安装的分类表已有旧目录，不补齐的话用户永远看不到新分类。
     *
     * 三条约定：
     * 1. **只增不删**：历史交易的 `category_name` 可能引用改名/下线的旧分类，删掉会让分类名游离在表外；
     * 2. **一级复用 id**：一级已存在时取它的 id 挂二级，不能为了拿 id 重复 insert（UNIQUE 冲突返回 -1）；
     * 3. **sortOrder 用 catalog 下标**：与 [insertCatalog] 同口径，存量项本来就是下标，新项天然接在末尾。
     *
     * @return 实际新增条数（一级 + 二级）；再跑一次返回 0
     */
    fun syncCatalog(): Int = dao.runInTransaction {
        var inserted = 0
        for ((type, seeds) in listOf(0 to CategoryCatalog.expense, 1 to CategoryCatalog.income)) {
            for ((index, seed) in seeds.withIndex()) {
                val existingTop = dao.findCategoryId(seed.name)
                val topId = if (existingTop > 0) {
                    existingTop
                } else {
                    dao.insertCategory(
                        Category(name = seed.name, type = type, parentId = -1, sortOrder = index)
                    ).also { if (it > 0) inserted++ }
                }
                if (topId <= 0) continue
                for ((childIndex, child) in seed.children.withIndex()) {
                    // 一级还在、二级也还在：跳过；只补新增项
                    if (dao.findCategoryId(child) > 0) continue
                    val id = dao.insertCategory(
                        Category(
                            name = child,
                            type = type,
                            parentId = topId,
                            sortOrder = childIndex,
                        )
                    )
                    if (id > 0) inserted++
                }
            }
        }
        inserted
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
