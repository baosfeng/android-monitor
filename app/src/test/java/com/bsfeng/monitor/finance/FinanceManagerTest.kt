package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [FinanceManager] 业务逻辑单元测试（Robolectric，真实 SQLite）。
 *
 * 覆盖种子数据幂等、月度统计、预算进度、预算实时已支出回填与交易增删改查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceManagerTest {

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

    private fun expense(
        amount: Double,
        date: String,
        category: String = "餐饮",
        remark: String = "",
    ) = Transaction(
        type = 0, categoryName = category, amount = amount,
        accountName = "支付宝", remark = remark,
        date = date, dateTime = "$date 12:00",
    )

    private fun income(amount: Double, date: String, category: String = "薪资") = Transaction(
        type = 1, categoryName = category, amount = amount,
        accountName = "支付宝", date = date, dateTime = "$date 09:00",
    )

    // ==================== 种子数据 ====================

    @Test
    fun `初始化播种默认分类与账户且重复调用不重复写入`() = runBlocking {
        manager.ensureInitialized()
        val categories = manager.getAllCategories()
        val accounts = manager.getAllAccounts()
        assertTrue("应播种默认分类", categories.isNotEmpty())
        assertTrue("应播种默认账户", accounts.isNotEmpty())

        manager.ensureInitialized()
        assertEquals("重复初始化不应新增分类", categories.size, manager.getAllCategories().size)
        assertEquals("重复初始化不应新增账户", accounts.size, manager.getAllAccounts().size)
    }

    @Test
    fun `按类型获取分类`() = runBlocking {
        manager.ensureInitialized()
        assertTrue(manager.getCategoriesByType(0).all { it.type == 0 })
        assertTrue(manager.getCategoriesByType(1).all { it.type == 1 })
    }

    // ==================== 交易增删改 ====================

    @Test
    fun `新增交易后可按月查询到`() = runBlocking {
        val id = manager.addTransaction(expense(amount = 25.0, date = "2026-01-05", remark = "早饭"))
        assertTrue(id > 0)

        val transactions = manager.getMonthlyTransactions("2026-01")
        assertEquals(1, transactions.size)
        assertEquals("早饭", transactions.first().remark)
        assertNotNull(manager.getTransactionById(id))
    }

    @Test
    fun `更新交易后金额与分类生效`() = runBlocking {
        val id = manager.addTransaction(expense(amount = 25.0, date = "2026-01-05"))
        val original = manager.getTransactionById(id)!!

        manager.updateTransaction(original.copy(amount = 99.0, categoryName = "购物"))

        val loaded = manager.getTransactionById(id)!!
        assertEquals(99.0, loaded.amount, 0.0001)
        assertEquals("购物", loaded.categoryName)
    }

    @Test
    fun `删除交易后按月查询为空`() = runBlocking {
        val id = manager.addTransaction(expense(amount = 25.0, date = "2026-01-05"))
        manager.deleteTransaction(id)
        assertTrue(manager.getMonthlyTransactions("2026-01").isEmpty())
        assertNull(manager.getTransactionById(id))
    }

    @Test
    fun `搜索与最近交易`() = runBlocking {
        manager.addTransaction(expense(amount = 10.0, date = "2026-01-05", remark = "火锅"))
        manager.addTransaction(expense(amount = 20.0, date = "2026-01-06", remark = "地铁"))

        assertEquals(1, manager.searchTransactions("火锅").size)
        assertEquals(2, manager.getRecentTransactions(limit = 10).size)
        assertEquals(1, manager.getRecentTransactions(limit = 1).size)
    }

    // ==================== 统计 ====================

    @Test
    fun `月度统计汇总收入支出与笔数`() = runBlocking {
        manager.addTransaction(expense(amount = 100.0, date = "2026-01-05", category = "餐饮"))
        manager.addTransaction(expense(amount = 50.0, date = "2026-01-06", category = "购物"))
        manager.addTransaction(income(amount = 8000.0, date = "2026-01-10"))

        val stats = manager.getMonthlyStats("2026-01")
        assertEquals(150.0, stats.totalExpense, 0.0001)
        assertEquals(8000.0, stats.totalIncome, 0.0001)
        assertEquals(3, stats.transactionCount)
        assertEquals("最高支出分类", "餐饮", stats.topCategory)
        assertEquals(7850.0, stats.balance, 0.0001)
    }

    @Test
    fun `月度统计不串月`() = runBlocking {
        manager.addTransaction(expense(amount = 100.0, date = "2026-01-05"))
        manager.addTransaction(expense(amount = 777.0, date = "2026-02-05"))

        assertEquals(100.0, manager.getMonthlyStats("2026-01").totalExpense, 0.0001)
        assertEquals(777.0, manager.getMonthlyStats("2026-02").totalExpense, 0.0001)
    }

    @Test
    fun `空月份统计为全零`() = runBlocking {
        val stats = manager.getMonthlyStats("2030-01")
        assertEquals(0.0, stats.totalExpense, 0.0001)
        assertEquals(0.0, stats.totalIncome, 0.0001)
        assertEquals(0, stats.transactionCount)
        assertEquals(0f, stats.budgetProgress, 0.0001f)
        assertEquals("", stats.topCategory)
    }

    @Test
    fun `分类占比按类型返回且降序`() = runBlocking {
        manager.addTransaction(expense(amount = 30.0, date = "2026-01-05", category = "餐饮"))
        manager.addTransaction(expense(amount = 90.0, date = "2026-01-06", category = "购物"))
        manager.addTransaction(income(amount = 500.0, date = "2026-01-07"))

        val expenseBreakdown = manager.getCategoryBreakdown("2026-01", 0)
        assertEquals(listOf("购物", "餐饮"), expenseBreakdown.keys.toList())

        val incomeBreakdown = manager.getCategoryBreakdown("2026-01", 1)
        assertEquals(listOf("薪资"), incomeBreakdown.keys.toList())
    }

    @Test
    fun `可用月份按倒序返回`() = runBlocking {
        manager.addTransaction(expense(amount = 10.0, date = "2026-01-05"))
        manager.addTransaction(expense(amount = 10.0, date = "2026-03-05"))
        assertEquals(listOf("2026-03", "2026-01"), manager.getAvailableMonths())
    }

    // ==================== 预算 ====================

    @Test
    fun `设置总预算后进度按当月支出计算`() = runBlocking {
        manager.setBudget("2026-01", "", 400.0)
        manager.addTransaction(expense(amount = 100.0, date = "2026-01-05"))

        val stats = manager.getMonthlyStats("2026-01")
        assertEquals(0.25f, stats.budgetProgress, 0.0001f)
        assertFalse(stats.isOverBudget)
    }

    @Test
    fun `支出超过总预算时进度封顶为 1 且判定超支`() = runBlocking {
        manager.setBudget("2026-01", "", 100.0)
        manager.addTransaction(expense(amount = 250.0, date = "2026-01-05"))

        val stats = manager.getMonthlyStats("2026-01")
        assertEquals(1f, stats.budgetProgress, 0.0001f)
        assertTrue(stats.isOverBudget)
    }

    @Test
    fun `未设置预算时进度为零`() = runBlocking {
        manager.addTransaction(expense(amount = 250.0, date = "2026-01-05"))
        assertEquals(0f, manager.getMonthlyStats("2026-01").budgetProgress, 0.0001f)
    }

    @Test
    fun `同月同分类重复设置预算覆盖旧值`() = runBlocking {
        manager.setBudget("2026-01", "", 400.0)
        manager.setBudget("2026-01", "", 800.0)

        val budgets = manager.getMonthlyBudget("2026-01")
        assertEquals(1, budgets.size)
        assertEquals(800.0, budgets.first().budgetAmount, 0.0001)
    }

    @Test
    fun `预算已支出按当月实际支出回填而不是导入时的历史快照`() = runBlocking {
        // 模拟熊猫记账导入留下的历史快照：预算 1000、已支出 999
        manager.dao.upsertBudget(
            com.bsfeng.monitor.finance.model.Budget(
                month = "2026-01", categoryName = "", budgetAmount = 1000.0, spentAmount = 999.0,
            )
        )
        manager.setBudget("2026-01", "餐饮", 300.0)

        manager.addTransaction(expense(amount = 120.0, date = "2026-01-05", category = "餐饮"))
        manager.addTransaction(expense(amount = 80.0, date = "2026-01-06", category = "购物"))
        // 其它月份的支出不得计入
        manager.addTransaction(expense(amount = 500.0, date = "2026-02-05", category = "餐饮"))

        val budgets = manager.getMonthlyBudget("2026-01")
        val overall = budgets.first { it.isOverall }
        val food = budgets.first { it.categoryName == "餐饮" }

        assertEquals("总预算已支出应为当月实际支出 200", 200.0, overall.spentAmount, 0.0001)
        assertEquals("分类预算已支出应为该分类当月支出 120", 120.0, food.spentAmount, 0.0001)
        assertEquals(0.2f, overall.progress, 0.0001f)
        assertEquals(0.4f, food.progress, 0.0001f)
    }

    @Test
    fun `没有预算时返回空列表`() = runBlocking {
        assertTrue(manager.getMonthlyBudget("2030-01").isEmpty())
    }

    // ==================== 变更通知（UI 自动刷新） ====================

    @Test
    fun `新增交易后变更版本递增`() = runBlocking {
        manager.ensureInitialized()
        val before = manager.changeVersion.value

        manager.addTransaction(expense(amount = 12.5, date = "2026-10-16"))

        assertEquals("一笔新增应 bump 一次", before + 1, manager.changeVersion.value)
    }

    @Test
    fun `改与删交易各 bump 一次`() = runBlocking {
        manager.ensureInitialized()
        val id = manager.addTransaction(expense(amount = 10.0, date = "2026-10-16"))
        val tx = manager.getTransactionById(id)
        assertNotNull(tx)
        val afterAdd = manager.changeVersion.value

        manager.updateTransaction(tx!!.copy(amount = 20.0))
        assertEquals("改一笔应 bump 一次", afterAdd + 1, manager.changeVersion.value)

        manager.deleteTransaction(id)
        assertEquals("删一笔应 bump 一次", afterAdd + 2, manager.changeVersion.value)
    }

    @Test
    fun `写预算也 bump 变更版本`() = runBlocking {
        manager.ensureInitialized()
        val before = manager.changeVersion.value

        manager.setBudget("2026-10", "", 1500.0)

        assertTrue("写预算应 bump 变更版本", manager.changeVersion.value > before)
    }

    @Test
    fun `只读查询不 bump 变更版本`() = runBlocking {
        manager.ensureInitialized()
        manager.addTransaction(expense(amount = 30.0, date = "2026-10-16"))
        val before = manager.changeVersion.value

        manager.getMonthlyTransactions("2026-10")
        manager.getMonthlyStats("2026-10")
        manager.getMonthlyBudget("2026-10")
        manager.getAvailableMonths()

        assertEquals("读路径不得 bump，否则 UI 刷新会自激", before, manager.changeVersion.value)
    }

    // ==================== 旧版自动记账拼接说明清理 ====================

    private fun legacyAuto(
        remark: String,
        sourceId: String,
        merchant: String = "淘宝闪购",
        productDesc: String = "淘宝闪购订单",
        payMethod: String = "招商银行信用卡(8888)",
    ) = Transaction(
        type = 0, categoryName = "购物", amount = 12.5,
        accountName = "支付宝", remark = remark,
        date = "2026-10-16", dateTime = "2026-10-16 12:00:00",
        source = "auto_alipay", sourceId = sourceId,
        merchant = merchant, productDesc = productDesc, payMethod = payMethod,
    )

    @Test
    fun `旧版拼接说明被清空且单据字段保留`() = runBlocking {
        manager.ensureInitialized()
        val id = manager.addTransaction(
            legacyAuto(
                remark = "淘宝闪购·淘宝闪购订单·招商银行信用卡(8888)",
                sourceId = "CFP000000000000000001",
            )
        )

        val cleaned = manager.cleanLegacyAggregatedRemarks()

        assertEquals("只应清理这一条拼接说明", 1, cleaned)
        val loaded = manager.getTransactionById(id)!!
        assertEquals("拼接说明应清空", "", loaded.remark)
        assertEquals("商户不能被清掉", "淘宝闪购", loaded.merchant)
        assertEquals("商品说明不能被清掉", "淘宝闪购订单", loaded.productDesc)
        assertEquals("付款方式不能被清掉", "招商银行信用卡(8888)", loaded.payMethod)
        assertEquals("金额不能被改动", 12.5, loaded.amount, 0.0001)
    }

    @Test
    fun `手写说明与手动记录不会被清理`() = runBlocking {
        manager.ensureInitialized()
        val manualId = manager.addTransaction(
            expense(amount = 10.0, date = "2026-10-16", remark = "同事聚餐")
        )
        val handWrittenId = manager.addTransaction(
            legacyAuto(remark = "同事聚餐 AA", sourceId = "CFP000000000000000002")
        )

        val cleaned = manager.cleanLegacyAggregatedRemarks()

        assertEquals("没有拼接说明就不应清理", 0, cleaned)
        assertEquals("同事聚餐", manager.getTransactionById(manualId)!!.remark)
        assertEquals("同事聚餐 AA", manager.getTransactionById(handWrittenId)!!.remark)
    }
}
