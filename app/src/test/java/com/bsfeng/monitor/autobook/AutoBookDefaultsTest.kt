package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 商户默认值测试（纯 JVM）。
 *
 * 需求：同一家店上次确认时选的类型 / 分类，这次默认勾选；没有历史就保持解析器结果。
 */
class AutoBookDefaultsTest {

    private fun draft(
        merchant: String = "星巴克咖啡",
        type: Int = AutoBookDraft.TYPE_EXPENSE,
        category: String = "购物",
    ): AutoBookDraft = AutoBookDraft(
        channel = AutoBookRules.CHANNEL_WECHAT,
        type = type,
        amount = 25.0,
        merchant = merchant,
        categoryName = category,
        accountName = "微信钱包",
        payMethod = "零钱",
        timeText = "2026-10-17 12:00:00",
        sourceId = "test:source",
        dedupKeys = emptyList(),
        markers = listOf("支付成功")
    )

    private fun last(type: Int, category: String): Transaction = Transaction(
        type = type,
        categoryName = category,
        amount = 25.0,
        accountName = "微信钱包",
        date = "2026-10-16",
        dateTime = "2026-10-16 12:00:00",
        merchant = "星巴克咖啡"
    )

    @Test
    fun `同商户有历史时默认上次选过的类型与分类`() {
        val merged = AutoBookDefaults.apply(draft(), last(type = AutoBookDraft.TYPE_EXPENSE, category = "餐饮"))
        assertEquals(AutoBookDraft.TYPE_EXPENSE, merged.type)
        assertEquals("餐饮", merged.categoryName)
    }

    @Test
    fun `没有历史保持解析器结果`() {
        val original = draft()
        assertSame(original, AutoBookDefaults.apply(original, null))
    }

    @Test
    fun `商户为空时不套用历史`() {
        val original = draft(merchant = "")
        val merged = AutoBookDefaults.apply(original, last(0, "餐饮"))
        assertSame("没有商户名就没有可匹配的店，必须原样返回", original, merged)
    }

    @Test
    fun `历史分类为空时只沿用类型`() {
        val merged = AutoBookDefaults.apply(
            draft(type = AutoBookDraft.TYPE_EXPENSE, category = "购物"),
            last(type = AutoBookDraft.TYPE_INCOME, category = "")
        )
        assertEquals(AutoBookDraft.TYPE_INCOME, merged.type)
        assertEquals("购物", merged.categoryName)
    }

    @Test
    fun `历史类型非法时忽略类型但保留分类`() {
        val merged = AutoBookDefaults.apply(
            draft(type = AutoBookDraft.TYPE_EXPENSE),
            last(type = 9, category = "餐饮")
        )
        assertEquals(AutoBookDraft.TYPE_EXPENSE, merged.type)
        assertEquals("餐饮", merged.categoryName)
    }
}
