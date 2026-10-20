package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交易详情页补全的纯逻辑测试：匹配 + 字段合并，全部不依赖 Android / 数据库。 */
class AutoBookDetailEnricherTest {

    private fun draft(
        amount: Double = 3.0,
        timeText: String = "2026-10-16 18:32:21",
        merchant: String = "阿里云计算有限公司",
        productDesc: String = "充值:阿里云服务购买",
        payMethod: String = "余额宝",
        orderId: String = "CFP000000000000000000",
        platformCategory: String = "日用百货",
        sourceId: String = "detail-source-id"
    ) = AutoBookDraft(
        channel = AutoBookRules.CHANNEL_ALIPAY,
        type = AutoBookDraft.TYPE_EXPENSE,
        amount = amount,
        merchant = merchant,
        categoryName = "其他",
        accountName = "支付宝",
        payMethod = payMethod,
        productDesc = productDesc,
        orderId = orderId,
        platformCategory = platformCategory,
        timeText = timeText,
        sourceId = sourceId,
        dedupKeys = emptyList(),
        markers = listOf("交易成功")
    )

    private fun booked(
        id: Long = 7L,
        amount: Double = 3.0,
        date: String = "2026-10-16",
        dateTime: String = "2026-10-16 18:32:00",
        merchant: String = "阿里云",
        productDesc: String = "",
        payMethod: String = "余额宝",
        orderId: String = "",
        createdAt: Long = 1_000L
    ) = Transaction(
        id = id,
        type = AutoBookDraft.TYPE_EXPENSE,
        categoryName = "购物",
        amount = amount,
        accountName = "支付宝",
        remark = merchant,
        date = date,
        dateTime = dateTime,
        source = "auto_alipay",
        sourceId = "old-source-id",
        merchant = merchant,
        productDesc = productDesc,
        payMethod = payMethod,
        orderId = orderId,
        createdAt = createdAt
    )

    @Test
    fun `按金额与时间窗口匹配最近的一笔`() {
        val near = booked(id = 1, dateTime = "2026-10-16 18:32:00")
        val far = booked(id = 2, dateTime = "2026-10-16 19:30:00")
        val match = AutoBookDetailEnricher.findMatch(
            listOf(far, near), draft(), nowMs = 10_000L
        )
        assertEquals(1L, match?.id)
    }

    @Test
    fun `时间对不上时不匹配`() {
        // 2026-10-17 用户要求：补全只认时间戳精确匹配，不再用「同一天最近一笔」这种猜法。
        val sameDay = booked(id = 3, dateTime = "2026-10-16 09:00:00")
        val otherDay = booked(id = 4, date = "2026-10-15", dateTime = "2026-10-15 18:32:00")
        assertNull(
            AutoBookDetailEnricher.findMatch(
                listOf(sameDay, otherDay), draft(), nowMs = 10_000L
            )
        )
    }

    @Test
    fun `详情页没给时间时不匹配`() {
        // 金额相同不代表同一笔：没有时间戳就放弃补全（宁可少补一次，也不改错账）。
        val recent = booked(id = 5, createdAt = 9_000L)
        val ancient = booked(id = 6, createdAt = 1L)
        assertNull(
            AutoBookDetailEnricher.findMatch(
                listOf(recent, ancient),
                draft(timeText = ""),
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun `窗口内两笔时间接近时不匹配`() {
        // 两笔记账时刻只差 5 秒、详情页时间是 18:32:21 → 分不清，放弃
        val a = booked(id = 8, dateTime = "2026-10-16 18:32:00")
        val b = booked(id = 9, dateTime = "2026-10-16 18:32:05")
        assertNull(
            AutoBookDetailEnricher.findMatch(listOf(a, b), draft(), nowMs = 10_000L)
        )
    }

    @Test
    fun `精确匹配优先于未补全的时间窗口`() {
        // 已补全过详情的那笔时间与详情页一致 → 必须先认它，不能配到邻近的未补全记录
        val enriched = booked(id = 10, dateTime = "2026-10-16 18:32:21").copy(
            tradeTime = "2026-10-16 18:32:21",
            orderId = "CFP000000000000000000"
        )
        val pending = booked(id = 11, dateTime = "2026-10-16 18:32:00")
        assertEquals(
            10L,
            AutoBookDetailEnricher.findMatch(listOf(pending, enriched), draft(), 10_000L)?.id
        )
    }

    @Test
    fun `金额不一致不得匹配`() {
        assertNull(
            AutoBookDetailEnricher.findMatch(listOf(booked(amount = 3.0)), draft(amount = 30.0), 10_000L)
        )
    }

    @Test
    fun `已补全过的同金额记录不会被另一笔详情页再次匹配`() {
        // 真机事故（2026-10-17）：16:49 与 16:51 两个 0.01 红包，16:49 的详情页把已补全的 16:51 记录又匹配了一次。
        // 已补全记录（tradeTime 非空）只参与 ±60 秒的精确匹配。
        val enriched = booked(id = 15, amount = 0.01, merchant = "张三")
            .copy(
                date = "2026-10-17",
                dateTime = "2026-10-17 16:51:24",
                tradeTime = "2026-10-17 16:51:24",
                platformCategory = "转账红包"
            )
        assertNull(
            AutoBookDetailEnricher.findMatch(
                listOf(enriched),
                draft(amount = 0.01, timeText = "2026-10-17 16:49:41"),
                nowMs = 10_000L
            )
        )
        // 同一笔详情页重进（时间一致）仍要匹配上
        assertEquals(
            15L,
            AutoBookDetailEnricher.findMatch(
                listOf(enriched),
                draft(amount = 0.01, timeText = "2026-10-17 16:51:24"),
                nowMs = 10_000L
            )?.id
        )
    }

    @Test
    fun `详情页商户为空时保留记账时的商户`() {
        // 支付宝红包详情页没有收款方字段，解析出的商户为空 —— 不能用空值覆盖「张三」。
        val result = AutoBookDetailEnricher.enrich(
            booked(merchant = "张三"),
            draft(merchant = ""),
            nowMs = 99_000L
        )
        assertEquals("张三", result.transaction.merchant)
    }

    @Test
    fun `补全字段但不改用户选的类型与分类`() {
        val existing = booked()
        val result = AutoBookDetailEnricher.enrich(existing, draft(), nowMs = 99_000L)
        assertTrue(result.changed)
        val tx = result.transaction
        // 用户选的类型 / 分类原样保留
        assertEquals(AutoBookDraft.TYPE_EXPENSE, tx.type)
        assertEquals("购物", tx.categoryName)
        assertEquals(3.0, tx.amount, 0.0001)
        // 详情页字段补进来
        assertEquals("阿里云计算有限公司", tx.merchant)
        assertEquals("充值:阿里云服务购买", tx.productDesc)
        assertEquals("CFP000000000000000000", tx.orderId)
        assertEquals("日用百货", tx.platformCategory)
        assertEquals("2026-10-16 18:32:21", tx.tradeTime)
        assertEquals("2026-10-16", tx.date)
        assertEquals("2026-10-16 18:32:21", tx.dateTime)
        assertEquals("detail-source-id", tx.sourceId)
        assertEquals(99_000L, tx.updatedAt)
        // 说明：用户写的文字原样保留，不追加商品说明 / 付款方式（2026-10-20 起说明只由用户写）
        assertEquals("阿里云", tx.remark)
        assertEquals("充值:阿里云服务购买", tx.productDesc)
        assertEquals("余额宝", tx.payMethod)
    }

    @Test
    fun `没有新增信息时 changed 为 false 且不更新`() {
        val existing = booked(
            merchant = "阿里云计算有限公司",
            productDesc = "充值:阿里云服务购买",
            payMethod = "余额宝",
            orderId = "CFP000000000000000000",
            dateTime = "2026-10-16 18:32:21"
        ).copy(
            platformCategory = "日用百货",
            tradeTime = "2026-10-16 18:32:21",
            sourceId = "detail-source-id",
            // 说明是用户自己写的，补全不会碰它 —— 所以这里放什么都应该 changed=false
            remark = "用户写的说明"
        )
        val result = AutoBookDetailEnricher.enrich(existing, draft(), nowMs = 99_000L)
        assertFalse(result.changed)
        assertEquals(existing, result.transaction)
    }
}
