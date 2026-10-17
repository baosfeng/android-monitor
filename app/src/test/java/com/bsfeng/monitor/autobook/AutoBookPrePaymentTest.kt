package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 付款前预采集 + 成功页合并测试（纯 JVM）。
 *
 * 真机场景（2026-10-17 微信转账）：付款前页面有收款方「张三(***)」、
 * 付款方式「零钱」、用户填的备注；支付成功页只有「支付成功 + ￥0.01 + 完成」。
 */
class AutoBookPrePaymentTest {

    private val wechat = AutoBookRules.wechat

    /** 微信转账输入页（真机日志 16:06:05） */
    private fun wechatTransferInput(): List<String> = listOf(
        "付款给个人 头像",
        "张三(***)",
        "金额",
        "¥ 0.01",
        "备注 午饭",
        "付款",
        "1 2 3 删除",
        "4 5 6",
        "7 8 9",
        "0 ."
    )

    /** 微信收银台（真机日志 16:06:08） */
    private fun wechatCashier(): List<String> = listOf(
        "使用密码",
        "返回",
        "付款给张三(***)",
        "￥0.01 请验证指纹",
        "付款方式,已选择零钱,更改 更改",
        "零钱",
        "支付"
    )

    @Test
    fun `转账输入页能预采集收款方与用户备注`() {
        val pre = AutoBookParser.parsePrePayment(wechat, wechatTransferInput(), 1L)
        requireNotNull(pre)
        assertEquals(0.01, pre.amount, 0.0001)
        assertEquals("张三(***)", pre.merchant)
        assertEquals("午饭", pre.remark)
    }

    @Test
    fun `「备注 修改」是入口文案不是用户备注`() {
        val lines = listOf(
            "付款给个人 头像",
            "张三(***)",
            "金额",
            "¥ 0.01",
            "备注 修⁠改", // 真机原文：中间夹了 U+2060 零宽单词连接符
            "付款"
        )
        val pre = AutoBookParser.parsePrePayment(wechat, lines, 1L)
        requireNotNull(pre)
        assertEquals("", pre.remark)
    }

    @Test
    fun `收银台也能预采集收款方与付款方式`() {
        val pre = AutoBookParser.parsePrePayment(wechat, wechatCashier(), 1L)
        requireNotNull(pre)
        assertEquals(0.01, pre.amount, 0.0001)
        assertEquals("张三(***)", pre.merchant)
        assertEquals("零钱", pre.payMethod)
    }

    @Test
    fun `结果页不会被当成付款前页面`() {
        val success = listOf("支付成功", "-¥0.01", "完成")
        assertEquals(null, AutoBookParser.parsePrePayment(wechat, success, 1L))
    }

    @Test
    fun `成功页补齐收款方_付款方式与备注`() {
        // 成功页：极简，只有状态 + 金额
        val result = AutoBookParser.parse(wechat, listOf("支付成功", "-¥0.01", "完成"), 1L)
        requireNotNull(result)
        // 付款前是分步的：输入页有收款方 + 备注，收银台页才有付款方式 —— 按字段累积
        val input = AutoBookParser.parsePrePayment(wechat, wechatTransferInput(), 0L)
        requireNotNull(input)
        val cashier = AutoBookParser.parsePrePayment(wechat, wechatCashier(), 1_000L)
        requireNotNull(cashier)
        val pre = AutoBookDraftMerger.accumulate(input, cashier)
        assertEquals("收银台页不能把输入页的备注冲掉", "午饭", pre.remark)

        val merged = AutoBookDraftMerger.merge(result, pre, nowMs = 30_000L)
        assertEquals("张三(***)", merged.merchant)
        assertEquals("零钱", merged.payMethod)
        assertEquals("午饭", merged.userRemark)
        assertEquals("成功页没有付款方式时用预采集", true, merged.remark().contains("午饭"))
    }

    @Test
    fun `成功页已有的字段不被预采集覆盖`() {
        val result = AutoBookParser.parse(
            wechat,
            listOf("支付成功", "-¥0.01", "商户全称 星巴克咖啡", "支付方式 零钱", "完成"),
            1L
        )
        requireNotNull(result)
        val pre = AutoBookParser.parsePrePayment(wechat, wechatTransferInput(), 0L)
        requireNotNull(pre)

        val merged = AutoBookDraftMerger.merge(result, pre, nowMs = 30_000L)
        assertEquals("星巴克咖啡", merged.merchant)
        assertEquals("零钱", merged.payMethod)
    }

    @Test
    fun `金额不一致或已过期时不合并`() {
        val result = AutoBookParser.parse(wechat, listOf("支付成功", "-¥0.01", "完成"), 1L)
        requireNotNull(result)
        val pre = AutoBookParser.parsePrePayment(wechat, wechatTransferInput(), 0L)
        requireNotNull(pre)

        // 金额不同
        val other = result.copy(amount = 9.99)
        assertSame("金额对不上时必须原样返回", other, AutoBookDraftMerger.merge(other, pre, 30_000L))
        // 过期
        assertSame(result, AutoBookDraftMerger.merge(result, pre, nowMs = AutoBookDraftMerger.PRE_DRAFT_TTL_MS + 1))
    }
}
