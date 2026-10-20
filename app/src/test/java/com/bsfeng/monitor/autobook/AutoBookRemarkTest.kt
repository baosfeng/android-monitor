package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本「说明」语义的纯逻辑测试（2026-10-20 用户要求：备注改成可编辑的说明）。
 *
 * 契约：说明只来自用户输入；商户 / 商品说明 / 付款方式各有结构化字段，不再拼进说明；
 * 浮窗里编辑过的值优先，空串就是不写说明。
 */
class AutoBookRemarkTest {

    private fun draft(
        userRemark: String = "",
        merchant: String = "星巴克咖啡",
        productDesc: String = "某某湘菜馆(示例店)外卖订单",
        payMethod: String = "招商银行信用卡(8888)",
    ) = AutoBookDraft(
        channel = AutoBookRules.CHANNEL_ALIPAY,
        type = AutoBookDraft.TYPE_EXPENSE,
        amount = 23.9,
        merchant = merchant,
        categoryName = "餐饮",
        accountName = "支付宝",
        payMethod = payMethod,
        productDesc = productDesc,
        timeText = "2026-10-20 12:00:00",
        sourceId = "remark-test-source-id",
        dedupKeys = emptyList(),
        markers = listOf("交易成功"),
        userRemark = userRemark,
    )

    @Test
    fun `说明只返回用户填的文字`() {
        assertEquals("午饭", draft(userRemark = "午饭").remark())
    }

    @Test
    fun `说明不拼接商户_商品说明与付款方式`() {
        // 改造前 remark() = 商户·商品说明·付款方式，账本列表里出现一长串重复文字
        val remark = draft().remark()
        assertEquals("用户没写说明就是空串", "", remark)
        assertFalse(remark.contains("星巴克咖啡"))
        assertFalse(remark.contains("外卖订单"))
        assertFalse(remark.contains("招商银行信用卡"))
    }

    @Test
    fun `说明去首尾空白`() {
        assertEquals("打车", draft(userRemark = "  打车  ").remark())
    }

    @Test
    fun `说明超长按上限截断`() {
        val long = "买".repeat(AutoBookDraft.REMARK_MAX_LENGTH + 20)
        val remark = draft(userRemark = long).remark()
        assertEquals(AutoBookDraft.REMARK_MAX_LENGTH, remark.length)
        assertTrue("截断只砍尾部", remark.all { it == '买' })
    }

    @Test
    fun `浮窗编辑后的值优先落库`() {
        // 预填的是付款前页面的备注，用户在浮窗里改成「加班打车」→ 落库值必须是编辑后的
        val d = draft(userRemark = "午饭")
        assertEquals("加班打车", d.bookedRemark("加班打车"))
    }

    @Test
    fun `没编辑时落库用预填说明`() {
        assertEquals("午饭", draft(userRemark = "午饭").bookedRemark())
    }

    @Test
    fun `清空说明后落库是空串`() {
        // 用户把预填的说明删干净：不能退回预填值，也不能编造内容
        val d = draft(userRemark = "午饭")
        assertEquals("", d.bookedRemark(""))
        assertEquals("", d.bookedRemark("   "))
    }

    @Test
    fun `编辑值落库前同样归一与截断`() {
        assertEquals("打车", draft().bookedRemark(" 打车 "))
        assertEquals(
            AutoBookDraft.REMARK_MAX_LENGTH,
            draft().bookedRemark("买".repeat(AutoBookDraft.REMARK_MAX_LENGTH + 5)).length
        )
    }
}
