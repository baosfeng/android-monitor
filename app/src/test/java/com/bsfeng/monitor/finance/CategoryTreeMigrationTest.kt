package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 分类体系迁移测试（Robolectric，真实 SQLite）。
 *
 * 验收点：旧交易/预算归到新一级；分类表变成一级 + 二级；重复迁移幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryTreeMigrationTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        manager = FinanceManager(context)
    }

    @After
    fun tearDown() {
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun `迁移把交易和预算归到新一级并建立两级分类`() = runBlocking {
        val txId = manager.dao.insertTransaction(
            Transaction(
                type = 0, categoryName = "打车", amount = 10.0,
                accountName = "支付宝", date = "2026-10-16",
            )
        )
        manager.dao.upsertBudget(
            Budget(month = "2026-10", categoryName = "水电", budgetAmount = 100.0)
        )

        val changed = manager.migrateCategoryTree()

        assertTrue("应改写了交易/预算行", changed > 0)
        assertEquals("打车应归到交通", "交通", manager.dao.getTransactionById(txId)!!.categoryName)
        assertEquals(
            "水电预算应归到居住",
            "居住",
            manager.dao.getBudgetsByMonth("2026-10").first().categoryName,
        )

        val categories = manager.dao.getAllCategories()
        val topLevel = categories.filter { it.parentId < 0 }
        assertEquals("支出一级 18 + 收入一级 8", 26, topLevel.size)
        val tree = CategoryTree(categories)
        assertEquals("购物", tree.topLevelNameOf("电商"))
        assertEquals("餐饮", tree.topLevelNameOf("外卖"))
        assertTrue("二级必须挂在某一级下", categories.any { it.parentId > 0 })
    }

    @Test
    fun `重复迁移不改变分类数量与已归一的名称`() = runBlocking {
        manager.dao.insertTransaction(
            Transaction(
                type = 0, categoryName = "手机数码", amount = 20.0,
                accountName = "支付宝", date = "2026-10-16",
            )
        )
        manager.migrateCategoryTree()
        val firstCount = manager.dao.getAllCategories().size

        manager.migrateCategoryTree()

        assertEquals("重复迁移不应重复插入分类", firstCount, manager.dao.getAllCategories().size)
        val tx = manager.dao.getTransactionsByMonth("2026-10").first()
        assertEquals("数码电子", tx.categoryName)
    }

    @Test
    fun `未知分类归到其他`() = runBlocking {
        val txId = manager.dao.insertTransaction(
            Transaction(
                type = 0, categoryName = "平台自定义分类", amount = 5.0,
                accountName = "支付宝", date = "2026-10-16",
            )
        )

        manager.migrateCategoryTree()

        assertEquals("其他", manager.dao.getTransactionById(txId)!!.categoryName)
    }
}
