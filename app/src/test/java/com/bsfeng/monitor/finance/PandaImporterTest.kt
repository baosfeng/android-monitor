package com.bsfeng.monitor.finance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [PandaImporter] 单元测试（Robolectric，自建熊猫记账格式 sqlite 夹具）。
 *
 * 覆盖字段映射、退款抵扣、重复导入去重、账户去重、
 * 文件不可用与表结构不兼容时的「错误可见 + 整体回滚」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PandaImporterTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager
    private lateinit var importer: PandaImporter
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        manager = FinanceManager(context)
        importer = PandaImporter(manager)
        dbFile = File(context.cacheDir, "panda-test.db")
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    /** 一条熊猫账单（t_bill 的一行） */
    private data class PandaBill(
        val id: Long,
        val category: String,
        val type: Int,
        val money: Double,
        val remark: String = "",
        val cardName: String = "支付宝",
        val tag: String = "",
        val date: String = "2026-01-05",
        val dateTime: String = "2026-01-05 08:00",
        val createTime: Long = 1_700_000_000_000L,
        val updateTime: Long = 1_700_000_000_000L,
    )

    private fun createPandaDb(
        bills: List<PandaBill> = emptyList(),
        categories: List<Triple<Long, String, Int>> = emptyList(),
        cards: List<Triple<String, Int, Double>> = emptyList(),
        budgets: List<Triple<String, Double, Double>> = emptyList(),
        billColumns: String = DEFAULT_BILL_COLUMNS,
    ): File {
        dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL("CREATE TABLE t_bill ($billColumns)")
            db.execSQL(
                "CREATE TABLE t_category (t_id INTEGER, category_name TEXT, type INTEGER, " +
                    "icon_normal TEXT, color TEXT, t_pid INTEGER, weight INTEGER)"
            )
            db.execSQL("CREATE TABLE t_card (id INTEGER, name TEXT, type INTEGER, money REAL, weight INTEGER)")
            db.execSQL("CREATE TABLE t_bbudget (t_id INTEGER, month TEXT, budget REAL, pay REAL)")

            bills.forEach { bill ->
                db.insert("t_bill", null, ContentValues().apply {
                    put("id", bill.id)
                    put("category", bill.category)
                    put("type", bill.type)
                    put("money", bill.money)
                    put("remark", bill.remark)
                    put("card_name", bill.cardName)
                    put("tag", bill.tag)
                    put("date", bill.date)
                    put("date_time", bill.dateTime)
                    put("createTime", bill.createTime)
                    put("updateTime", bill.updateTime)
                    put("pics", "")
                })
            }
            categories.forEachIndexed { index, (id, name, type) ->
                db.insert("t_category", null, ContentValues().apply {
                    put("t_id", id)
                    put("category_name", name)
                    put("type", type)
                    put("icon_normal", "")
                    put("color", "")
                    put("t_pid", -1)
                    put("weight", index)
                })
            }
            cards.forEachIndexed { index, (name, type, money) ->
                db.insert("t_card", null, ContentValues().apply {
                    put("id", index + 1)
                    put("name", name)
                    put("type", type)
                    put("money", money)
                    put("weight", index)
                })
            }
            budgets.forEachIndexed { index, (month, budget, pay) ->
                db.insert("t_bbudget", null, ContentValues().apply {
                    put("t_id", index + 1)
                    put("month", month)
                    put("budget", budget)
                    put("pay", pay)
                })
            }
        } finally {
            db.close()
        }
        return dbFile
    }

    // ==================== 正常路径 ====================

    @Test
    fun `完整导入账单分类账户与预算并统计各计数`() = runBlocking {
        val file = createPandaDb(
            bills = listOf(
                PandaBill(id = 1, category = "餐饮", type = 0, money = 25.5, remark = "早饭", date = "2026-01-05", dateTime = "2026-01-05 08:00"),
                PandaBill(id = 2, category = "薪资", type = 1, money = 8000.0, date = "2026-01-10", dateTime = "2026-01-10 09:00"),
            ),
            categories = listOf(Triple(1L, "餐饮", 0), Triple(2L, "薪资", 1)),
            cards = listOf(Triple("招商银行", 1, 1234.0)),
            budgets = listOf(Triple("2026-01", 3000.0, 25.5)),
        )

        val result = importer.import(file.absolutePath)

        assertEquals(2, result.totalTransactions)
        assertEquals(2, result.importedTransactions)
        assertEquals(0, result.skippedTransactions)
        assertEquals(2, result.importedCategories)
        assertEquals(1, result.importedAccounts)
        assertEquals(1, result.importedBudgets)
        assertFalse("正常导入不应有错误", result.hasErrors)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `账单字段映射到交易模型`() = runBlocking {
        val file = createPandaDb(
            bills = listOf(
                PandaBill(
                    id = 42, category = "餐饮", type = 0, money = 25.5, remark = "早饭",
                    cardName = "微信钱包", tag = "工作日",
                    date = "2026-01-05", dateTime = "2026-01-05 08:30",
                )
            )
        )

        importer.import(file.absolutePath)

        val tx = manager.getMonthlyTransactions("2026-01").single()
        assertEquals(0, tx.type)
        assertEquals("餐饮", tx.categoryName)
        assertEquals(25.5, tx.amount, 0.0001)
        assertEquals("微信钱包", tx.accountName)
        assertEquals("早饭", tx.remark)
        assertEquals("工作日", tx.tag)
        assertEquals("2026-01-05", tx.date)
        assertEquals("2026-01-05 08:30", tx.dateTime)
        assertEquals(PandaImporter.SOURCE_PANDA, tx.source)
        assertEquals("42", tx.sourceId)
    }

    @Test
    fun `收入账单保持收入类型与正金额`() = runBlocking {
        val file = createPandaDb(
            bills = listOf(PandaBill(id = 1, category = "薪资", type = 1, money = 8000.0))
        )
        importer.import(file.absolutePath)

        val tx = manager.getMonthlyTransactions("2026-01").single()
        assertEquals(1, tx.type)
        assertTrue(tx.isIncome)
        assertEquals(8000.0, manager.getMonthlyStats("2026-01").totalIncome, 0.0001)
        assertEquals(0.0, manager.getMonthlyStats("2026-01").totalExpense, 0.0001)
    }

    @Test
    fun `分类与账户类型按熊猫规则映射`() = runBlocking {
        val file = createPandaDb(
            categories = listOf(Triple(1L, "餐饮", 0), Triple(2L, "薪资", 1)),
            cards = listOf(
                Triple("现金钱包", 0, 10.0),
                Triple("招商银行", 1, 20.0),
                Triple("信用卡", 2, 30.0),
                Triple("支付宝", 3, 40.0),
            ),
        )
        importer.import(file.absolutePath)

        val accounts = manager.getAllAccounts().associateBy { it.name }
        assertEquals(0, accounts["现金钱包"]!!.type)
        assertEquals(1, accounts["招商银行"]!!.type)
        assertEquals("信用卡归入银行卡", 1, accounts["信用卡"]!!.type)
        assertEquals(2, accounts["支付宝"]!!.type)
        assertEquals(40.0, accounts["支付宝"]!!.balance, 0.0001)

        val categories = manager.getAllCategories().associateBy { it.name }
        assertEquals(0, categories["餐饮"]!!.type)
        assertEquals(1, categories["薪资"]!!.type)
    }

    @Test
    fun `预算按月份与金额导入`() = runBlocking {
        val file = createPandaDb(budgets = listOf(Triple("2026-01", 3000.0, 120.0)))
        importer.import(file.absolutePath)

        val budget = manager.dao.getBudgetsByMonth("2026-01").single()
        assertEquals(3000.0, budget.budgetAmount, 0.0001)
        assertTrue("熊猫总预算映射为不限分类的总预算", budget.isOverall)
    }

    @Test
    fun `熊猫库中没有账单时导入成功但计数为零`() = runBlocking {
        val file = createPandaDb()
        val result = importer.import(file.absolutePath)

        assertEquals(0, result.totalTransactions)
        assertEquals(0, result.importedTransactions)
        assertFalse(result.hasErrors)
        assertFalse("没有账单时不算成功导入", result.isSuccess)
        assertTrue(manager.getMonthlyTransactions("2026-01").isEmpty())
    }

    // ==================== 退款（缺陷复现：曾虚增支出） ====================

    @Test
    fun `退款账单存为负金额支出并抵扣当月支出`() = runBlocking {
        val file = createPandaDb(
            bills = listOf(
                PandaBill(id = 1, category = "餐饮", type = 0, money = 100.0, date = "2026-01-05", dateTime = "2026-01-05 12:00"),
                PandaBill(id = 2, category = "餐饮", type = 3, money = 30.0, date = "2026-01-06", dateTime = "2026-01-06 12:00"),
            )
        )

        val result = importer.import(file.absolutePath)
        assertEquals(2, result.importedTransactions)

        val refund = manager.getMonthlyTransactions("2026-01").first { it.sourceId == "2" }
        assertEquals("退款按支出类型存储", 0, refund.type)
        assertEquals("退款金额必须为负", -30.0, refund.amount, 0.0001)
        assertTrue(refund.isRefundEntry)
        assertEquals("展示金额取绝对值，不出现双负号", "30.00", refund.displayAmount)
        assertEquals("当月支出应被退款抵扣为 70", 70.0, manager.getMonthlyStats("2026-01").totalExpense, 0.0001)
    }

    @Test
    fun `未知账单类型按支出处理且金额取原值`() = runBlocking {
        val file = createPandaDb(bills = listOf(PandaBill(id = 1, category = "其他", type = 9, money = 12.0)))
        importer.import(file.absolutePath)

        val tx = manager.getMonthlyTransactions("2026-01").single()
        assertEquals(0, tx.type)
        assertEquals(12.0, tx.amount, 0.0001)
    }

    // ==================== 去重 ====================

    @Test
    fun `同一数据库重复导入时跳过已有账单`() = runBlocking {
        val file = createPandaDb(
            bills = listOf(
                PandaBill(id = 1, category = "餐饮", type = 0, money = 25.0),
                PandaBill(id = 2, category = "购物", type = 0, money = 45.0),
            )
        )

        val first = importer.import(file.absolutePath)
        assertEquals(2, first.importedTransactions)

        val second = importer.import(file.absolutePath)
        assertEquals(2, second.totalTransactions)
        assertEquals("重复导入不应新增", 0, second.importedTransactions)
        assertEquals(2, second.skippedTransactions)
        assertEquals(2, manager.getMonthlyTransactions("2026-01").size)
    }

    @Test
    fun `已存在的同名账户不重复计入导入数量`() = runBlocking {
        manager.ensureInitialized()
        val before = manager.getAllAccounts().size

        val file = createPandaDb(cards = listOf(Triple("支付宝", 3, 0.0), Triple("新账户", 0, 5.0)))
        val result = importer.import(file.absolutePath)

        assertEquals("只应新增「新账户」一个", 1, result.importedAccounts)
        assertEquals(before + 1, manager.getAllAccounts().size)
    }

    @Test
    fun `已存在的同名分类不重复计入导入数量`() = runBlocking {
        manager.ensureInitialized()

        val file = createPandaDb(
            categories = listOf(Triple(1L, "餐饮", 0), Triple(2L, "熊猫独有分类", 0))
        )
        val result = importer.import(file.absolutePath)

        assertEquals("默认分类已含「餐饮」，只应新增一个", 1, result.importedCategories)
    }

    // ==================== 异常路径 ====================

    @Test
    fun `数据库文件不存在时返回错误而不抛异常`() = runBlocking {
        val missing = File(context.cacheDir, "not-exist-panda.db")
        val result = importer.import(missing.absolutePath)

        assertTrue(result.hasErrors)
        assertEquals(0, result.importedTransactions)
        assertTrue(
            "错误信息应说明无法打开数据库文件",
            result.errors.any { it.contains("无法打开数据库文件") },
        )
    }

    @Test
    fun `账单表结构不兼容时错误可见且整体回滚`() = runBlocking {
        // t_bill 缺少 money 列：读取账单阶段必然失败
        val file = createPandaDb(
            categories = listOf(Triple(1L, "餐饮", 0)),
            cards = listOf(Triple("支付宝", 3, 0.0)),
            budgets = listOf(Triple("2026-01", 3000.0, 0.0)),
            billColumns = "id INTEGER PRIMARY KEY, category TEXT, type INTEGER, remark TEXT",
        )

        val result = importer.import(file.absolutePath)

        assertTrue("失败必须暴露错误，不得静默", result.hasErrors)
        assertTrue(
            "错误信息应指明导入失败并回滚",
            result.errors.any { it.contains("已回滚") },
        )
        assertEquals(0, result.importedTransactions)
        assertEquals(0, result.importedCategories)
        assertEquals(0, result.importedAccounts)
        assertEquals(0, result.importedBudgets)
        assertTrue("回滚后不应残留分类", manager.getAllCategories().isEmpty())
        assertTrue("回滚后不应残留账户", manager.getAllAccounts().isEmpty())
        assertTrue("回滚后不应残留预算", manager.getMonthlyBudget("2026-01").isEmpty())
    }

    private companion object {
        const val DEFAULT_BILL_COLUMNS =
            "id INTEGER PRIMARY KEY, category TEXT, type INTEGER, money REAL, remark TEXT, " +
                "card_name TEXT, tag TEXT, date TEXT, date_time TEXT, createTime INTEGER, " +
                "updateTime INTEGER, pics TEXT"
    }
}
