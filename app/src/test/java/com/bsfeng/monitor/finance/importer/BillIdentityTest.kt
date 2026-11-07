package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BillIdentity] 三档身份键。
 *
 * 最要紧的两条：**不含商户名**（商户名写法在两次导出里会变）、
 * **金额两位小数归一**（3.4 与 3.40 必须算出同一个键），否则同一笔会记两次。
 */
class BillIdentityTest {

    private fun record(
        orderId: String = "",
        tradeTime: String = "",
        amount: Double = 0.0,
        counterparty: String = "",
        direction: BillDirection = BillDirection.EXPENSE,
        channel: BillChannel = BillChannel.ALIPAY,
    ) = BillRecord(
        channel = channel,
        orderId = orderId,
        tradeTime = tradeTime,
        amount = amount,
        counterparty = counterparty,
        direction = direction,
    )

    @Test
    fun `有单号时用订单号档`() {
        val r = record(orderId = "2026110622001", tradeTime = "2026-11-06 20:40:51", amount = 3.0)
        assertEquals("order:alipay_import:2026110622001", BillIdentity.sourceId(r))
    }

    @Test
    fun `无单号时用时间金额档`() {
        val r = record(tradeTime = "2026-11-06 20:40:51", amount = 3.0)
        assertEquals("time:alipay_import:0:3.00:2026-11-06 20:40", BillIdentity.sourceId(r))
    }

    @Test
    fun `收入方向在键里有区分`() {
        val r = record(tradeTime = "2026-11-06 20:40", amount = 100.0, direction = BillDirection.INCOME)
        assertEquals("time:alipay_import:1:100.00:2026-11-06 20:40", BillIdentity.sourceId(r))
    }

    @Test
    fun `两档都不成立时返回 null`() {
        assertNull(BillIdentity.sourceId(record(amount = 3.0)))
        assertNull(BillIdentity.sourceId(record(tradeTime = "", amount = 3.0)))
        // 有时间没金额也算不成立：金额是这一档的必要条件
        assertNull(BillIdentity.sourceId(record(tradeTime = "2026-11-06 20:40", amount = 0.0)))
    }

    @Test
    fun `身份键不含商户名`() {
        val a = record(tradeTime = "2026-11-06 20:40", amount = 12.5, counterparty = "某某科技")
        val b = record(tradeTime = "2026-11-06 20:40", amount = 12.5, counterparty = "某某科技有限公司")
        assertEquals(BillIdentity.sourceId(a), BillIdentity.sourceId(b))
    }

    @Test
    fun `金额小数位归一`() {
        val a = record(tradeTime = "2026-11-06 20:40", amount = 3.4)
        val b = record(tradeTime = "2026-11-06 20:40", amount = 3.40)
        assertEquals(BillIdentity.sourceId(a), BillIdentity.sourceId(b))
    }

    @Test
    fun `渠道不同则键不同`() {
        val ali = record(tradeTime = "2026-11-06 20:40", amount = 3.0, channel = BillChannel.ALIPAY)
        val wx = record(tradeTime = "2026-11-06 20:40", amount = 3.0, channel = BillChannel.WECHAT)
        assertTrue(BillIdentity.sourceId(ali) != BillIdentity.sourceId(wx))
    }

    @Test
    fun `时间归一兼容三种写法`() {
        assertEquals("2026-11-06 20:40", BillIdentity.normalizeMinute("2026-11-06 20:40:51"))
        assertEquals("2026-11-06 20:40", BillIdentity.normalizeMinute("2026/11/06 20:40"))
        assertEquals("2026-11-06 20:40", BillIdentity.normalizeMinute("2026年11月6日 20:40"))
        assertNull(BillIdentity.normalizeMinute("不是时间"))
        assertNull(BillIdentity.normalizeMinute(""))
    }

    @Test
    fun `落库的日期与时间戳取页面交易时间`() {
        val r = record(tradeTime = "2026-11-06 20:40:51", amount = 3.0)
        assertEquals("2026-11-06", BillIdentity.dateOf(r))
        assertEquals("2026-11-06 20:40:51", BillIdentity.dateTimeOf(r))
        // 缺秒补 00，不能给空日期（空日期会让这笔账在按月查询里彻底消失）
        val noSecond = record(tradeTime = "2026-11-06 20:40", amount = 3.0)
        assertEquals("2026-11-06 20:40:00", BillIdentity.dateTimeOf(noSecond))
    }
}
