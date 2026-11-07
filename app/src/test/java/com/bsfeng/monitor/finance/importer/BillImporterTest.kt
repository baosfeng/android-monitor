package com.bsfeng.monitor.finance.importer

import android.content.Context
import com.bsfeng.monitor.finance.FinanceDatabaseHelper
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [BillImporter] 入库语义（Robolectric + 真实 SQLite）。
 *
 * 覆盖设计文档的三条不变式：身份键去重、跨源命中只更新、**只补空不覆盖**。
 * 样例数据全部自造（「某某」系列商户名、整数金额），不含任何真实账单。
 */
// application = Application：MonitorApp.onCreate 会后台补分类目录，与这里的空库断言抢 finance.db
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BillImporterTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager
    private lateinit var importer: BillImporter

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        manager = FinanceManager(context)
        importer = BillImporter(manager)
    }

    @After
    fun tearDown() {
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    private fun expenseRow(
        orderId: String = "",
        tradeTime: String = "",
        amount: Double = 0.0,
        counterparty: String = "",
        product: String = "",
        payMethod: String = "",
        platformCategory: String = "",
        lineNo: Int = 0,
        channel: BillChannel = BillChannel.ALIPAY,
    ) = BillRecord(
        channel = channel,
        orderId = orderId,
        tradeTime = tradeTime,
        amount = amount,
        direction = BillDirection.EXPENSE,
        counterparty = counterparty,
        product = product,
        payMethod = payMethod,
        platformCategory = platformCategory,
        lineNo = lineNo,
    )

    private fun all(): List<Transaction> = manager.dao.getRecentTransactions(100)

    @Test
    fun `新记录插入并按关键词推断分类`() {
        val result = importer.importRecords(
            listOf(
                expenseRow(
                    orderId = "A001", tradeTime = "2026-11-01 12:30:21", amount = 38.0,
                    counterparty = "某某湘菜馆", product = "某某湘菜馆外卖订单", payMethod = "余额宝",
                    platformCategory = "餐饮美食", lineNo = 2,
                ),
            )
        );
        assertEquals(1, result.inserted)
        assertEquals(0, result.failed)
        val tx = all().single()
        // 商品名「某某湘菜馆外卖订单」命中关键词「外卖」（表内「外卖」先于「正餐」）
        assertEquals("外卖", tx.categoryName)
        assertEquals("alipay_import", tx.source)
        assertEquals("order:alipay_import:A001", tx.sourceId)
        assertEquals("支付宝", tx.accountName)
        assertEquals(38.0, tx.amount, 0.001)
        assertEquals("2026-11-01", tx.date)
        assertEquals("2026-11-01 12:30:21", tx.dateTime)
        assertEquals("某某湘菜馆", tx.merchant)
        assertEquals("余额宝", tx.payMethod)
        assertEquals("餐饮美食", tx.platformCategory)
    }

    @Test
    fun `同一笔导入两次只入一条`() {
        val row = expenseRow(orderId = "A002", tradeTime = "2026-11-02 09:00:00", amount = 12.0, counterparty = "某某便利店")
        assertEquals(1, importer.importRecords(listOf(row)).inserted)
        val second = importer.importRecords(listOf(row))
        assertEquals("第二次不应新增", 0, second.inserted)
        assertEquals("字段没变化时算未变更", 1, second.unchanged)
        assertEquals(1, all().size)
    }

    @Test
    fun `跨源命中自动记账已写的记录 只补空不新增`() {
        // 自动记账先记了一笔：分类被用户改过、说明是手写的
        manager.dao.insertTransaction(
            Transaction(
                type = 0, categoryName = "正餐", amount = 66.0, accountName = "支付宝",
                remark = "我自己写的说明", merchant = "", productDesc = "", payMethod = "",
                date = "2026-11-03", dateTime = "2026-11-03 19:20:00",
                source = "auto_alipay", sourceId = "auto-1", orderId = "A003",
            )
        )
        val result = importer.importRecords(
            listOf(
                expenseRow(
                    orderId = "A003", tradeTime = "2026-11-03 19:20:00", amount = 66.0,
                    counterparty = "某某火锅店", product = "某某火锅店堂食", payMethod = "招商银行(8888)",
                ),
            )
        );
        assertEquals("跨源命中不应新增", 0, result.inserted)
        assertEquals(1, result.updated)
        val tx = all().single()
        assertEquals("分类不能被覆盖", "正餐", tx.categoryName)
        assertEquals("说明不能被覆盖", "我自己写的说明", tx.remark)
        assertEquals(66.0, tx.amount, 0.001)
        assertEquals("空商户被补上", "某某火锅店", tx.merchant)
        assertEquals("空商品说明被补上", "某某火锅店堂食", tx.productDesc)
        assertEquals("空付款方式被补上", "招商银行(8888)", tx.payMethod)
        assertEquals("已有来源不变", "auto_alipay", tx.source)
    }

    @Test
    fun `只补空不覆盖 已有值全部保留`() {
        manager.dao.insertTransaction(
            Transaction(
                type = 0, categoryName = "外卖", amount = 20.0, accountName = "支付宝",
                merchant = "已有商户", productDesc = "已有商品", payMethod = "已有方式",
                date = "2026-11-04", dateTime = "2026-11-04 12:00:00",
                source = "alipay_import", sourceId = "order:alipay_import:A004", orderId = "A004",
                platformCategory = "已有平台分类",
            )
        )
        val result = importer.importRecords(
            listOf(
                expenseRow(
                    orderId = "A004", tradeTime = "2026-11-04 12:00:00", amount = 20.0,
                    counterparty = "新商户", product = "新商品", payMethod = "新方式", platformCategory = "新平台分类",
                ),
            )
        );
        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        val tx = all().single()
        assertEquals("已有商户", tx.merchant)
        assertEquals("已有商品", tx.productDesc)
        assertEquals("已有方式", tx.payMethod)
        assertEquals("已有平台分类", tx.platformCategory)
        assertEquals("外卖", tx.categoryName)
    }

    @Test
    fun `跳过行不计入失败也不入库`() {
        val result = importer.importRecords(
            listOf(
                expenseRow(orderId = "A005", tradeTime = "2026-11-05 08:00:00", amount = 5.0)
                    .copy(direction = BillDirection.SKIP),
            )
        );
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(0, result.inserted)
        assertTrue(all().isEmpty())
    }

    @Test
    fun `既无单号又无时间计入失败并带行号`() {
        val result = importer.importRecords(
            listOf(expenseRow(orderId = "", tradeTime = "", amount = 5.0, lineNo = 7))
        );
        assertEquals(1, result.failed)
        assertEquals(0, result.inserted)
        assertTrue(result.errors.single().contains("第 7 行"))
        assertTrue(all().isEmpty())
    }

    @Test
    fun `收入行按收入表推断且 type 为 1`() {
        val row = BillRecord(
            channel = BillChannel.WECHAT, orderId = "W001", tradeTime = "2026-11-06 09:00:00",
            amount = 8000.0, direction = BillDirection.INCOME, product = "代发工资",
        )
        assertEquals(1, importer.importRecords(listOf(row)).inserted)
        val tx = all().single()
        assertEquals(1, tx.type)
        assertEquals("工资", tx.categoryName)
        assertEquals("wechat_import", tx.source)
        assertEquals("微信钱包", tx.accountName)
    }

    @Test
    fun `空列表直接返回且不算失败`() {
        val result = importer.importRecords(emptyList())
        assertEquals(0, result.totalRows)
        assertEquals(0, result.failed)
        assertTrue(!result.hasWrites)
    }

    @Test
    fun `解析失败转成失败明细而不是抛异常`() = kotlinx.coroutines.runBlocking {
        val result = importer.import("alipay.csv", ByteArray(0))
        assertEquals(1, result.failed)
        assertNotNull(result.errors.firstOrNull())
    }
}