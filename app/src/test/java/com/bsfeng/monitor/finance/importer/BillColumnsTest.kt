package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BillColumns] 单元测试（纯 JVM）。
 *
 * 验收点：表头按内容探测（不看行号）、两家的列名写法都能归一、列顺序变了仍按列名取值、缺列不报错。
 * 样例表头均为自造文本。
 */
class BillColumnsTest {

    private val alipayHeader = "交易号,交易创建时间,商品名称,交易对方,金额（元）,收/支,交易状态,支付方式,支付宝交易分类"

    private val wechatHeader = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号"

    @Test
    fun `支付宝 4 行元信息后是表头行`() {
        val lines = listOf(
            "示例账单明细",
            "账号:[test***@example.com]",
            "起始日期:[2026-10-01] 终止日期:[2026-11-01]",
            "-----------------交易记录明细列表-----------------",
            alipayHeader,
            "2026110600000001,2026-11-06 20:40:51,示例外卖订单,示例商户,¥12.34,支出,交易成功,余额宝,餐饮美食",
        )
        assertEquals(4, BillColumns.detectHeaderRow(lines))
    }

    @Test
    fun `微信 5 行元信息后是表头行`() {
        val lines = listOf(
            "微信支付账单明细,,,,,,,,",
            "微信昵称:[测试昵称],,,,,,,,",
            "起始时间:[2026-10-01],,,,,,,,",
            "共 2 笔记录,,,,,,,,",
            ",,,,,,,,",
            wechatHeader,
            "2026-11-06 20:40:51,商户消费,示例商户,示例商品,支出,¥12.34,零钱,支付成功,1000000000001",
        )
        assertEquals(5, BillColumns.detectHeaderRow(lines))
    }

    @Test
    fun `列顺序颠倒仍按列名取值`() {
        val shuffled = "金额（元）,交易创建时间,交易号,收/支,商品名称,交易对方"
        val columns = BillColumns.normalizeHeader(BillCsv.parseLine(shuffled))
        assertEquals(0, columns[BillField.AMOUNT])
        assertEquals(1, columns[BillField.TRADE_TIME])
        assertEquals(2, columns[BillField.ORDER_ID])
        assertEquals(3, columns[BillField.DIRECTION])
        assertEquals(4, columns[BillField.PRODUCT])
        assertEquals(5, columns[BillField.COUNTERPARTY])

        val cells = BillCsv.parseLine("\"¥1,234.56\",2026-11-06 20:40:51,2026110600000001,支出,示例商品,示例商户")
        assertEquals("¥1,234.56", cellValue(cells, columns, BillField.AMOUNT))
        assertEquals("2026110600000001", cellValue(cells, columns, BillField.ORDER_ID))
    }

    @Test
    fun `缺列时其余字段照常归一 不存在的字段返回空串`() {
        val header = "交易号,交易创建时间,金额,收/支"
        val columns = BillColumns.normalizeHeader(BillCsv.parseLine(header))
        assertTrue(columns.containsKey(BillField.AMOUNT))
        assertTrue(!columns.containsKey(BillField.PAY_METHOD))
        assertEquals("", cellValue(listOf("1", "2", "3", "4"), columns, BillField.PAY_METHOD))
        assertEquals("3", cellValue(listOf("1", "2", "3", "4"), columns, BillField.AMOUNT))
    }

    @Test
    fun `金额与时间缺一就不算表头`() {
        assertNull(BillColumns.detectHeaderRow(listOf("交易号,商品名称,交易对方", "1,2,3")))
        assertNull(BillColumns.detectHeaderRow(emptyList()))
    }

    @Test
    fun `字段别名覆盖两家写法且全角括号等价`() {
        val alipay = BillColumns.normalizeHeader(BillCsv.parseLine(alipayHeader))
        val wechat = BillColumns.normalizeHeader(BillCsv.parseLine(wechatHeader))
        assertEquals(0, alipay[BillField.ORDER_ID])
        assertEquals(8, wechat[BillField.ORDER_ID])
        assertEquals(1, alipay[BillField.TRADE_TIME])
        assertEquals(0, wechat[BillField.TRADE_TIME])
        assertEquals(2, alipay[BillField.PRODUCT])
        assertEquals(3, wechat[BillField.PRODUCT])
        assertEquals(8, alipay[BillField.PLATFORM_CATEGORY])
        assertEquals("金额(元)", BillColumns.normalizeHeaderText(" 金额（元） "))
    }

    @Test
    fun `表头只在前 20 行里找`() {
        val meta = (1..25).map { "元信息行 $it" }
        assertNull(BillColumns.detectHeaderRow(meta + alipayHeader))
    }
}
