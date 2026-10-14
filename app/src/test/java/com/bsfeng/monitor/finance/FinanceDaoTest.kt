package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [FinanceDao] 单元测试（Robolectric，运行真实 SQLite）。
 *
 * 覆盖交易 CRUD、按月/按范围查询、月度汇总（含退款抵扣）、分类占比、
 * 去重判定、预算唯一约束与事务回滚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceDaoTest {

    private lateinit var context: Context
    private lateinit var dbHelper: FinanceDatabaseHelper
    private lateinit var dao: FinanceDao

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 单例可能被其它用例污染：释放后重新建库，保证每个用例从空库开始
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        dbHelper = FinanceDatabaseHelper(context)
        dao = FinanceDao(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    private fun tx(
        type: Int = 0,
        category: String = "餐饮",
        amount: Double = 10.0,
        date: String = "2026-01-15",
        dateTime: String = "2026-01-15 12:00",
        account: String = "支付宝",
        remark: String = "",
        tag: String = "",
        source: String = "manual",
        sourceId: String = "",
    ) = Transaction(
        type = type,
        categoryName = category,
        amount = amount,
        accountName = account,
        remark = remark,
        tag = tag,
        date = date,
        dateTime = dateTime,
        source = source,
        sourceId = sourceId,
    )

    // ==================== 交易 CRUD ====================

    @Test
    fun `插入后可按 ID 查回全部字段`() {
        val id = dao.insertTransaction(
            tx(amount = 42.5, remark = "午饭", tag = "工作日", sourceId = "9")
        )
        assertTrue("自增主键应大于 0", id > 0)

        val loaded = dao.getTransactionById(id)
        assertNotNull(loaded)
        assertEquals(0, loaded!!.type)
        assertEquals("餐饮", loaded.categoryName)
        assertEquals(42.5, loaded.amount, 0.0001)
        assertEquals("支付宝", loaded.accountName)
        assertEquals("午饭", loaded.remark)
        assertEquals("工作日", loaded.tag)
        assertEquals("2026-01-15", loaded.date)
        assertEquals("2026-01-15 12:00", loaded.dateTime)
        assertEquals("manual", loaded.source)
        assertEquals("9", loaded.sourceId)
    }

    @Test
    fun `按 ID 查询不存在的交易返回 null`() {
        assertNull(dao.getTransactionById(9999L))
    }

    @Test
    fun `更新交易后字段生效且可查回`() {
        val id = dao.insertTransaction(tx(amount = 10.0, category = "餐饮"))
        val original = dao.getTransactionById(id)!!
        val updated = original.copy(amount = 88.0, categoryName = "购物", remark = "改过")
        assertEquals(1, dao.updateTransaction(updated))

        val loaded = dao.getTransactionById(id)!!
        assertEquals(88.0, loaded.amount, 0.0001)
        assertEquals("购物", loaded.categoryName)
        assertEquals("改过", loaded.remark)
        assertEquals("更新不应改变主键", id, loaded.id)
    }

    @Test
    fun `更新不存在的交易返回 0 行`() {
        assertEquals(0, dao.updateTransaction(tx().copy(id = 12345L)))
    }

    @Test
    fun `删除交易后查不到`() {
        val id = dao.insertTransaction(tx())
        assertEquals(1, dao.deleteTransaction(id))
        assertNull(dao.getTransactionById(id))
        assertEquals(0, dao.deleteTransaction(id))
    }

    // ==================== 按月 / 按范围查询 ====================

    @Test
    fun `按月份查询只返回该月数据且按日期降序`() {
        dao.insertTransaction(tx(date = "2026-01-01", dateTime = "2026-01-01 08:00"))
        dao.insertTransaction(tx(date = "2026-01-31", dateTime = "2026-01-31 20:00"))
        dao.insertTransaction(tx(date = "2026-02-01", dateTime = "2026-02-01 09:00"))
        dao.insertTransaction(tx(date = "2025-12-31", dateTime = "2025-12-31 23:59"))

        val january = dao.getTransactionsByMonth("2026-01")
        assertEquals(2, january.size)
        assertEquals("2026-01-31", january.first().date)
        assertEquals("2026-01-01", january.last().date)
    }

    @Test
    fun `跨年月份查询不越界`() {
        dao.insertTransaction(tx(date = "2025-12-31"))
        dao.insertTransaction(tx(date = "2026-01-01"))

        assertEquals(1, dao.getTransactionsByMonth("2025-12").size)
        assertEquals(1, dao.getTransactionsByMonth("2026-01").size)
    }

    @Test
    fun `空月份查询返回空列表`() {
        assertTrue(dao.getTransactionsByMonth("2030-05").isEmpty())
    }

    @Test
    fun `日期范围查询包含首尾两端`() {
        dao.insertTransaction(tx(date = "2026-01-01"))
        dao.insertTransaction(tx(date = "2026-01-10"))
        dao.insertTransaction(tx(date = "2026-01-20"))

        val range = dao.getTransactionsByDateRange("2026-01-01", "2026-01-10")
        assertEquals(2, range.size)
    }

    @Test
    fun `最近交易按限制条数返回`() {
        repeat(5) { index ->
            dao.insertTransaction(tx(date = "2026-01-0${index + 1}", amount = index + 1.0))
        }
        val recent = dao.getRecentTransactions(limit = 3)
        assertEquals(3, recent.size)
        assertEquals("2026-01-05", recent.first().date)
    }

    // ==================== 月度汇总 ====================

    @Test
    fun `月度支出汇总把退款作为抵扣计算`() {
        dao.insertTransaction(tx(type = 0, amount = 100.0))
        dao.insertTransaction(tx(type = 0, amount = 50.0))
        // 熊猫记账退款：支出 + 负金额
        dao.insertTransaction(tx(type = 0, amount = -30.0, category = "退款"))
        dao.insertTransaction(tx(type = 1, amount = 20.0, category = "红包"))

        assertEquals("支出应为 100+50-30", 120.0, dao.getMonthlyExpenseTotal("2026-01"), 0.0001)
        assertEquals("收入只统计 type=1", 20.0, dao.getMonthlyIncomeTotal("2026-01"), 0.0001)
    }

    @Test
    fun `空月份汇总为 0`() {
        assertEquals(0.0, dao.getMonthlyExpenseTotal("2030-01"), 0.0001)
        assertEquals(0.0, dao.getMonthlyIncomeTotal("2030-01"), 0.0001)
    }

    @Test
    fun `月度汇总不串月`() {
        dao.insertTransaction(tx(type = 0, amount = 100.0, date = "2026-01-15"))
        dao.insertTransaction(tx(type = 0, amount = 999.0, date = "2026-02-15"))
        assertEquals(100.0, dao.getMonthlyExpenseTotal("2026-01"), 0.0001)
        assertEquals(999.0, dao.getMonthlyExpenseTotal("2026-02"), 0.0001)
    }

    // ==================== 分类占比 ====================

    @Test
    fun `分类占比按金额降序且只统计指定类型`() {
        dao.insertTransaction(tx(type = 0, category = "餐饮", amount = 30.0))
        dao.insertTransaction(tx(type = 0, category = "餐饮", amount = 20.0))
        dao.insertTransaction(tx(type = 0, category = "购物", amount = 100.0))
        dao.insertTransaction(tx(type = 1, category = "薪资", amount = 9999.0))

        val breakdown = dao.getMonthlyCategoryBreakdown("2026-01", 0)
        assertEquals(2, breakdown.size)
        assertEquals(listOf("购物", "餐饮"), breakdown.keys.toList())
        assertEquals(100.0, breakdown["购物"]!!, 0.0001)
        assertEquals(50.0, breakdown["餐饮"]!!, 0.0001)
    }

    @Test
    fun `无数据时分类占比为空`() {
        assertTrue(dao.getMonthlyCategoryBreakdown("2030-01", 0).isEmpty())
    }

    // ==================== 可用月份 / 去重 ====================

    @Test
    fun `可用月份去重并按倒序返回`() {
        dao.insertTransaction(tx(date = "2026-01-01"))
        dao.insertTransaction(tx(date = "2026-01-20"))
        dao.insertTransaction(tx(date = "2026-03-05"))
        dao.insertTransaction(tx(date = "2025-12-31"))

        assertEquals(listOf("2026-03", "2026-01", "2025-12"), dao.getAvailableMonths())
    }

    @Test
    fun `可用月份为空时返回空列表`() {
        assertTrue(dao.getAvailableMonths().isEmpty())
    }

    @Test
    fun `transactionExists 按来源与来源 ID 联合判定`() {
        dao.insertTransaction(tx(source = "panda_import", sourceId = "77"))

        assertTrue(dao.transactionExists("panda_import", "77"))
        assertFalse("来源 ID 相同但来源不同不算已导入", dao.transactionExists("manual", "77"))
        assertFalse(dao.transactionExists("panda_import", "78"))
    }

    @Test
    fun `批量插入返回实际写入条数`() {
        val inserted = dao.insertTransactions(listOf(tx(date = "2026-01-01"), tx(date = "2026-01-02")))
        assertEquals(2, inserted)
        assertEquals(0, dao.insertTransactions(emptyList()))
    }

    @Test
    fun `搜索按备注与分类名模糊匹配`() {
        dao.insertTransaction(tx(category = "餐饮", remark = "和同事吃火锅"))
        dao.insertTransaction(tx(category = "购物", remark = "买鞋"))
        dao.insertTransaction(tx(category = "交通", remark = "地铁"))

        assertEquals(1, dao.searchTransactions("火锅").size)
        assertEquals(1, dao.searchTransactions("购物").size)
        assertEquals(3, dao.searchTransactions("").size)
        assertTrue(dao.searchTransactions("不存在的关键词").isEmpty())
    }

    // ==================== 分类 / 账户 / 预算 ====================

    @Test
    fun `批量插入分类忽略同名并返回新增数量`() {
        val first = dao.insertCategories(
            listOf(Category(name = "餐饮", type = 0), Category(name = "购物", type = 0))
        )
        assertEquals(2, first)

        val second = dao.insertCategories(
            listOf(Category(name = "餐饮", type = 0), Category(name = "交通", type = 0))
        )
        assertEquals("同名分类不应重复插入", 1, second)
        assertEquals(3, dao.getAllCategories().size)
        assertEquals(0, dao.insertCategories(emptyList()))
    }

    @Test
    fun `按类型获取分类只返回该类型`() {
        dao.insertCategories(
            listOf(
                Category(name = "餐饮", type = 0),
                Category(name = "薪资", type = 1),
            )
        )
        assertEquals(listOf("餐饮"), dao.getCategoriesByType(0).map { it.name })
        assertEquals(listOf("薪资"), dao.getCategoriesByType(1).map { it.name })
    }

    @Test
    fun `插入同名账户返回已有账户 ID`() {
        val firstId = dao.insertAccount(Account(name = "支付宝", balance = 100.0))
        val secondId = dao.insertAccount(Account(name = "支付宝", balance = 999.0))

        assertEquals("同名账户应复用已有记录", firstId, secondId)
        assertEquals(1, dao.getAllAccounts().size)
        assertEquals(100.0, dao.getAccountByName("支付宝")!!.balance, 0.0001)
    }

    @Test
    fun `按名称查询不存在的账户返回 null`() {
        assertNull(dao.getAccountByName("不存在的账户"))
    }

    @Test
    fun `更新账户余额`() {
        val id = dao.insertAccount(Account(name = "现金钱包", balance = 50.0))
        assertEquals(1, dao.updateAccountBalance(id, 500.0))
        assertEquals(500.0, dao.getAccountByName("现金钱包")!!.balance, 0.0001)
    }

    @Test
    fun `同月同分类预算写入两次时覆盖旧值`() {
        dao.upsertBudget(Budget(month = "2026-01", categoryName = "", budgetAmount = 1000.0))
        dao.upsertBudget(Budget(month = "2026-01", categoryName = "", budgetAmount = 2000.0))

        val budgets = dao.getBudgetsByMonth("2026-01")
        assertEquals("唯一约束应让同月同分类只有一条", 1, budgets.size)
        assertEquals(2000.0, budgets.first().budgetAmount, 0.0001)
    }

    @Test
    fun `预算查询只返回指定月份`() {
        dao.upsertBudget(Budget(month = "2026-01", categoryName = "", budgetAmount = 1000.0))
        dao.upsertBudget(Budget(month = "2026-01", categoryName = "餐饮", budgetAmount = 300.0))
        dao.upsertBudget(Budget(month = "2026-02", categoryName = "", budgetAmount = 800.0))

        assertEquals(2, dao.getBudgetsByMonth("2026-01").size)
        assertEquals(1, dao.getBudgetsByMonth("2026-02").size)
        assertTrue(dao.getBudgetsByMonth("2030-01").isEmpty())
    }

    // ==================== 事务 ====================

    @Test
    fun `runInTransaction 正常提交并返回结果`() {
        val id = dao.runInTransaction { dao.insertTransaction(tx()) }
        assertTrue(id > 0)
        assertNotNull(dao.getTransactionById(id))
    }

    @Test
    fun `runInTransaction 抛异常时整体回滚`() {
        val before = dao.getTransactionsByMonth("2026-01").size

        try {
            dao.runInTransaction {
                dao.insertTransaction(tx(date = "2026-01-20"))
                dao.insertCategories(listOf(Category(name = "事务内分类", type = 0)))
                throw IllegalStateException("模拟导入中途失败")
            }
            fail("事务块内的异常必须向上抛出，不能被吞掉")
        } catch (expected: IllegalStateException) {
            // 预期
        }

        assertEquals("交易写入必须回滚", before, dao.getTransactionsByMonth("2026-01").size)
        assertTrue("分类写入必须回滚", dao.getAllCategories().isEmpty())
    }
}
