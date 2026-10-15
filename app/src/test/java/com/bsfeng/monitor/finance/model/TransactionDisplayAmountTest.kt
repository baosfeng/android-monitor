package com.bsfeng.monitor.finance.model

import com.bsfeng.monitor.finance.ui.formatMoney
import com.bsfeng.monitor.finance.ui.yen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Transaction.displayAmount] 的金额口径（纯 JVM）。
 *
 * 口径（见 [Transaction.displayAmount] 的 KDoc）：交易行 = 千分位分组 + 固定 2 位小数；
 * 汇总 / KPI / 预算 = 同一个 `%,.Nf` 源（[formatMoney] / [yen]），只差小数位。
 * 本测试守住这两条，防止金额展示再次分叉成两套格式。
 */
class TransactionDisplayAmountTest {

    @Test
    fun `千位以上金额按千分位分组并固定两位小数`() {
        assertEquals("1,200.00", Transaction(type = 1, amount = 1200.0).displayAmount)
        assertEquals("2,468.50", Transaction(type = 0, amount = 2468.5).displayAmount)
        assertEquals("1,234,567.89", Transaction(type = 1, amount = 1234567.89).displayAmount)
    }

    @Test
    fun `不足千位不加分隔符且补齐两位小数`() {
        assertEquals("0.00", Transaction(amount = 0.0).displayAmount)
        assertEquals("38.00", Transaction(amount = 38.0).displayAmount)
        assertEquals("38.50", Transaction(amount = 38.5).displayAmount)
        assertEquals("999.99", Transaction(type = 1, amount = 999.99).displayAmount)
    }

    @Test
    fun `负数支出取绝对值展示不出现双负号`() {
        assertEquals("30.00", Transaction(type = 0, amount = -30.0).displayAmount)
        assertEquals("1,200.00", Transaction(type = 0, amount = -1200.0).displayAmount)
    }

    @Test
    fun `交易行与汇总同源：只差小数位`() {
        // 原型 s04-tx-6 交易行 +¥1,200.00 / s04-krow 汇总 ¥2,468
        assertEquals("¥1,200", yen(1200.0))
        assertEquals("2,468", formatMoney(2468.0))
        assertEquals("1,200.00", formatMoney(1200.0, 2))
    }
}
