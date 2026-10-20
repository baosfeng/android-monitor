package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.finance.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「记一笔」纯逻辑测试（对照定版原型 s14a）。
 *
 * 覆盖三条路径：正常（能选到「昨天 10 点」）、边界（首尾日期、时分 00、金额 0）、
 * 异常（日期非法 / 金额非数字 / 转账选了同一个账户）。
 */
class RecordFormTest {

    private val today = LocalDate.of(2026, 10, 16)

    // ===== 日期快捷 =====

    @Test
    fun `日期快捷默认给今天昨天前天再加两天`() {
        val shortcuts = RecordForm.dateShortcuts(today)
        // 前三个用相对标签，再往前就只能是绝对日期（否则「前天」会和「10 月 14 日」撞车）
        assertEquals(
            listOf("今天", "昨天", "前天", "10 月 13 日", "10 月 12 日"),
            shortcuts.map { it.label },
        )
        assertEquals(
            listOf("2026-10-16", "2026-10-15", "2026-10-14", "2026-10-13", "2026-10-12"),
            shortcuts.map { it.date },
        )
    }

    @Test
    fun `日期快捷可为 0 时只给三个相对标签`() {
        val shortcuts = RecordForm.dateShortcuts(today, extraDays = 0)
        assertEquals(listOf("今天", "昨天", "前天"), shortcuts.map { it.label })
    }

    @Test
    fun `日期快捷天数为负时按 0 处理`() {
        val shortcuts = RecordForm.dateShortcuts(today, extraDays = -3)
        assertEquals(3, shortcuts.size)
    }

    @Test
    fun `日期快捷跨月与跨年边界正确`() {
        assertEquals("2026-02-27", RecordForm.dateShortcuts(LocalDate.of(2026, 3, 1))[2].date)
        assertEquals("2025-12-30", RecordForm.dateShortcuts(LocalDate.of(2026, 1, 1))[2].date)
    }

    // ===== 时间行与时间戳 =====

    @Test
    fun `时间行相对标签覆盖今天昨天前天`() {
        val now = RecordForm.TimeParts("2026-10-16", 18, 32, 21)
        assertEquals("今天 18:32:21", RecordForm.timeRowText(now, today))
        assertEquals(
            "昨天 09:05:00",
            RecordForm.timeRowText(now.copy(date = "2026-10-15", hour = 9, minute = 5, second = 0), today),
        )
        assertEquals(
            "前天 23:59:59",
            RecordForm.timeRowText(now.copy(date = "2026-10-14", hour = 23, minute = 59, second = 59), today),
        )
    }

    @Test
    fun `时间行更早日期用月日`() {
        val parts = RecordForm.TimeParts("2026-10-01", 10, 30, 0)
        assertEquals("10 月 1 日 10:30:00", RecordForm.timeRowText(parts, today))
    }

    @Test
    fun `时间行日期为空时退回今天且日期非法不抛异常`() {
        assertEquals("今天 08:00:00", RecordForm.timeRowText(RecordForm.TimeParts("", 8, 0, 0), today))
        assertEquals(
            "不是日期 08:00:00",
            RecordForm.timeRowText(RecordForm.TimeParts("不是日期", 8, 0, 0), today),
        )
    }

    @Test
    fun `时间戳精确到年月日时分秒`() {
        assertEquals(
            "2026-10-15 10:30:00",
            RecordForm.timestampOf(RecordForm.TimeParts("2026-10-15", 10, 30, 0)),
        )
        assertEquals(
            "2026-01-02 00:00:07",
            RecordForm.timestampOf(RecordForm.TimeParts("2026-01-02", 0, 0, 7)),
        )
    }

    @Test
    fun `时间解析支持到秒与只到分两种形态`() {
        val withSeconds = RecordForm.timePartsOf("2026-10-16", "2026-10-16 18:32:21", LocalDateTime.of(2026, 1, 1, 0, 0, 0))
        assertEquals(RecordForm.TimeParts("2026-10-16", 18, 32, 21), withSeconds)

        val minutesOnly = RecordForm.timePartsOf("2026-10-16", "2026-10-16 18:32", LocalDateTime.of(2026, 1, 1, 0, 0, 0))
        assertEquals(RecordForm.TimeParts("2026-10-16", 18, 32, 0), minutesOnly)
    }

    @Test
    fun `时间解析日期为空时采用 dateTime 里的日期`() {
        val parts = RecordForm.timePartsOf("", "2026-10-15 10:30:00", LocalDateTime.of(2026, 1, 1, 0, 0, 0))
        assertEquals("2026-10-15", parts.date)
    }

    @Test
    fun `时间解析失败时退回当前时刻`() {
        val now = LocalDateTime.of(2026, 10, 16, 21, 5, 9)
        val parts = RecordForm.timePartsOf("", "", now)
        assertEquals(RecordForm.TimeParts("2026-10-16", 21, 5, 9), parts)
        val broken = RecordForm.timePartsOf("2026-10-16", "昨天 18:32", now)
        assertEquals(RecordForm.TimeParts("2026-10-16", 21, 5, 9), broken)
    }

    // ===== 可保存判定 =====

    @Test
    fun `支出收入需要金额与分类`() {
        assertTrue(RecordForm.canSave(RecordForm.TYPE_EXPENSE, "23.9", "餐饮", "支付宝", ""))
        assertTrue(RecordForm.canSave(RecordForm.TYPE_INCOME, "8800", "薪资", "支付宝", ""))
        assertFalse("金额为 0 不可保存", RecordForm.canSave(RecordForm.TYPE_EXPENSE, "0", "餐饮", "支付宝", ""))
        assertFalse("金额为空不可保存", RecordForm.canSave(RecordForm.TYPE_EXPENSE, "", "餐饮", "支付宝", ""))
        assertFalse("未选分类不可保存", RecordForm.canSave(RecordForm.TYPE_EXPENSE, "23.9", "", "支付宝", ""))
    }

    @Test
    fun `金额非数字按 0 处理而不是抛异常`() {
        assertEquals(0.0, RecordForm.amountOf("abc"), 0.0001)
        assertEquals(0.0, RecordForm.amountOf(""), 0.0001)
        assertEquals(23.9, RecordForm.amountOf("23.9"), 0.0001)
        assertFalse(RecordForm.canSave(RecordForm.TYPE_EXPENSE, "abc", "餐饮", "支付宝", ""))
    }

    @Test
    fun `转账要求转出转入是两个不同账户且不需要分类`() {
        assertTrue(RecordForm.canSave(RecordForm.TYPE_TRANSFER, "500", "", "支付宝", "现金钱包"))
        assertFalse("分类为空不影响转账", RecordForm.canSave(RecordForm.TYPE_TRANSFER, "500", "", "支付宝", ""))
        assertFalse(
            "转出转入同一账户不算转账",
            RecordForm.canSave(RecordForm.TYPE_TRANSFER, "500", "", "支付宝", "支付宝"),
        )
        assertFalse(
            "转账也要有金额",
            RecordForm.canSave(RecordForm.TYPE_TRANSFER, "0", "", "支付宝", "现金钱包"),
        )
    }

    @Test
    fun `切换类型后校正分类`() {
        assertEquals("薪资", RecordForm.correctCategory("餐饮", listOf("薪资", "奖金")))
        assertEquals("餐饮", RecordForm.correctCategory("餐饮", listOf("餐饮", "出行")))
        assertEquals("", RecordForm.correctCategory("餐饮", emptyList()))
    }

    // ===== 单据与转账标题 =====

    private val receiptTx = Transaction(
        merchant = "淘宝闪购",
        productDesc = "某某湘菜馆(示例店)外卖订单",
        payMethod = "招商银行信用卡(8888)",
        platformCategory = "餐饮美食",
    )

    @Test
    fun `单据项保留六项里所有有值的字段`() {
        val items = RecordForm.receiptItems(receiptTx)
        assertEquals(listOf("商户", "商品说明", "付款方式", "平台分类"), items.map { it.first })
        assertTrue(RecordForm.hasReceipt(receiptTx))
        assertFalse("手动录入的交易没有单据", RecordForm.hasReceipt(Transaction()))
        assertFalse(RecordForm.hasReceipt(null))
        assertTrue(RecordForm.receiptItems(null).isEmpty())
    }

    @Test
    fun `单据摘要优先商户与付款方式`() {
        assertEquals("淘宝闪购 · 招商银行信用卡(8888)", RecordForm.receiptSummary(receiptTx))
        assertEquals(
            "只有商品说明时用它兜底",
            "外卖订单",
            RecordForm.receiptSummary(Transaction(productDesc = "外卖订单")),
        )
        assertEquals("", RecordForm.receiptSummary(Transaction()))
    }

    @Test
    fun `转账标题给出转出转入账户`() {
        assertEquals(
            "支付宝 → 现金钱包",
            RecordForm.transferTitle(Transaction(type = 2, accountName = "支付宝", toAccountName = "现金钱包")),
        )
        assertEquals("转账", RecordForm.transferTitle(Transaction(type = 2, accountName = "支付宝")))
        assertEquals("转账", RecordForm.transferTitle(Transaction(type = 2)))
    }

    // ===== 列表行副标题（说明 → 单据摘要） =====

    @Test
    fun `列表副标题优先说明为空时回退单据摘要`() {
        // 有手写说明时只显示说明：商户 / 付款方式不再拼进来
        assertEquals("同事聚餐 AA", RecordForm.subtitle(receiptTx.copy(remark = "同事聚餐 AA")))
        // 说明为空（自动记账的常态）：回退「商户 · 付款方式」
        assertEquals("淘宝闪购 · 招商银行信用卡(8888)", RecordForm.subtitle(receiptTx))
        // 商户与付款方式都缺：退回商品说明
        assertEquals("外卖订单", RecordForm.subtitle(Transaction(productDesc = "外卖订单")))
        // 手动记账且没写说明：这一行没有副标题
        assertEquals("", RecordForm.subtitle(Transaction()))
        assertEquals("", RecordForm.subtitle(null))
    }

    @Test
    fun `列表副标题去掉说明两端空白并清掉只有空白的说明`() {
        assertEquals("午餐", RecordForm.subtitle(Transaction(remark = "  午餐  ")))
        assertEquals("淘宝闪购", RecordForm.subtitle(Transaction(remark = "   ", merchant = "淘宝闪购")))
    }
}
