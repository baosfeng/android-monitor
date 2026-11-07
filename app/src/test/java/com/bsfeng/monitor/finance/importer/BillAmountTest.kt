package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 金额解析与方向判定的单元测试（纯 JVM）。样例金额均自造。
 */
class BillAmountTest {

    @Test
    fun `人民币符号与千分位被去掉`() {
        assertEquals(1234.56, BillFileParser.parseAmount("¥1,234.56") ?: -1.0, 0.0001)
        assertEquals(1234.56, BillFileParser.parseAmount("￥1,234.56") ?: -1.0, 0.0001)
        assertEquals(1234.56, BillFileParser.parseAmount("1,234.56") ?: -1.0, 0.0001)
        assertEquals(1234.56, BillFileParser.parseAmount("1234.56") ?: -1.0, 0.0001)
        assertEquals(0.01, BillFileParser.parseAmount("¥0.01") ?: -1.0, 0.0001)
    }

    @Test
    fun `带元字与负号的金额也能读`() {
        assertEquals(-12.5, BillFileParser.parseAmount("-12.50元") ?: 1.0, 0.0001)
    }

    @Test
    fun `解析不出的文本返回 null`() {
        assertNull(BillFileParser.parseAmount(""))
        assertNull(BillFileParser.parseAmount("¥"))
        assertNull(BillFileParser.parseAmount("见备注"))
    }

    @Test
    fun `收支列决定方向 空列按金额符号兜底`() {
        assertEquals(BillDirection.INCOME, BillFileParser.directionOf("收入", 12.0))
        assertEquals(BillDirection.EXPENSE, BillFileParser.directionOf("支出", 12.0))
        assertEquals(BillDirection.SKIP, BillFileParser.directionOf("不计收支", 12.0))
        assertEquals(BillDirection.EXPENSE, BillFileParser.directionOf("", -12.0))
        assertEquals(BillDirection.INCOME, BillFileParser.directionOf("", 12.0))
    }

    @Test
    fun `交易关闭 全额退款 提现 都判为跳过`() {
        assertEquals(true, BillFileParser.shouldSkip("支出", "交易关闭", "示例商品", "示例商户"))
        assertEquals(true, BillFileParser.shouldSkip("支出", "已全额退款", "示例商品", "示例商户"))
        assertEquals(true, BillFileParser.shouldSkip("", "交易成功", "转账到本人", ""))
        assertEquals(true, BillFileParser.shouldSkip("", "交易成功", "", "提现"))
        assertEquals(false, BillFileParser.shouldSkip("支出", "交易成功", "示例商品", "示例商户"))
    }
}
