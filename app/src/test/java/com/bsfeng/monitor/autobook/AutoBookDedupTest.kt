package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短期去重表测试（纯 JVM）。
 *
 * 覆盖的核心场景来自真机：同一笔账单被反复触发时，**只能弹一次浮窗**。
 */
class AutoBookDedupTest {

    private fun key(value: String, ttlMs: Long = 10 * 60 * 1000L) = DedupKey(value, ttlMs)

    @Test
    fun `没处理过的账单可以弹窗`() {
        val dedup = AutoBookDedup()
        assertTrue(dedup.shouldShow(listOf(key("order:alipay:CFP001")), 1_000L))
    }

    @Test
    fun `确认记账后 10 分钟内不再弹`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("order:alipay:CFP001"))
        dedup.markConfirmed(keys, 0L)

        assertFalse("页面继续抛事件也不能复弹", dedup.shouldShow(keys, 60_000L))
        val reason = dedup.blockedReason(keys, 60_000L)
        assertNotNull(reason)
        assertTrue("原因要说清是「已记账」，排查「为什么没弹」时才能和识别失败区分开", reason!!.contains("已记账"))
    }

    @Test
    fun `确认记账超过安静期后可以再弹`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("order:alipay:CFP001", AutoBookDedup.CONFIRMED_TTL_MS))
        dedup.markConfirmed(keys, 0L)
        assertTrue(dedup.shouldShow(keys, AutoBookDedup.CONFIRMED_TTL_MS))
    }

    @Test
    fun `忽略后同样进入安静期`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("sum:alipay:0:3.0", 10 * 60 * 1000L))
        dedup.markDismissed(keys, 0L)

        assertFalse(dedup.shouldShow(keys, 5 * 60 * 1000L))
        assertTrue(dedup.blockedReason(keys, 5 * 60 * 1000L)!!.contains("已忽略"))
        assertTrue("超过安静期后可以再弹", dedup.shouldShow(keys, 10 * 60 * 1000L))
    }

    @Test
    fun `浮窗弹过后只做短暂防抖`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("sum:wechat:0:12.3", 2 * 60 * 1000L))
        dedup.markShown(keys, 0L)

        assertFalse(dedup.shouldShow(keys, 29_000L))
        assertTrue("30 秒后允许再弹（用户没操作，可能只是浮窗被系统收起）", dedup.shouldShow(keys, 30_000L))
    }

    @Test
    fun `三种结局互相覆盖：忽略后又确认，按最后一次算`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("order:alipay:CFP001"))
        dedup.markShown(keys, 0L)
        dedup.markDismissed(keys, 1_000L)
        dedup.markConfirmed(keys, 2_000L)

        assertTrue("最后一次是确认，日志应显示已记账", dedup.blockedReason(keys, 3_000L)!!.contains("已记账"))
    }

    @Test
    fun `字段抖动导致指纹变化时仍能认出同一笔`() {
        val dedup = AutoBookDedup()
        // 第一次识别：页面还没渲染完，只有兜底指纹
        val first = listOf(key("sum:alipay:0:3.0", 2 * 60 * 1000L))
        // 第二次识别：渲染完成，多了精确指纹
        val second = listOf(
            key("time:alipay:0:3.0:2026-10-15 23:14:43"),
            key("sum:alipay:0:3.0", 2 * 60 * 1000L)
        )

        dedup.markShown(first, 1_000L)
        assertFalse(
            "同一笔不该因为多出精确指纹就再弹一次（真机就是这么连弹两次的）",
            dedup.shouldShow(second, 10_000L)
        )
    }

    @Test
    fun `跨过旧的 5 分钟时间窗边界后仍是同一笔`() {
        // 改造前的根因：指纹里带了「5 分钟时间桶」，跨桶就换键 —— 同一笔在边界后又一次弹出
        val dedup = AutoBookDedup()
        val precise = listOf(key("time:alipay:0:3.0:2026-10-15 23:14:43"))
        dedup.markConfirmed(precise, 0L)

        assertFalse("跨 5 分钟桶边界不得复弹", dedup.shouldShow(precise, 300_000L))
        assertFalse("9 分钟后仍不得复弹", dedup.shouldShow(precise, 540_000L))
    }

    @Test
    fun `兜底指纹的安静期取较短的窗口`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("sum:alipay:0:3.0", 2 * 60 * 1000L))
        dedup.markConfirmed(keys, 0L)

        assertFalse("2 分钟内仍被挡住", dedup.shouldShow(keys, 119_000L))
        assertTrue("超过兜底窗口后放行，同金额的另一笔交易不能被永久吞掉", dedup.shouldShow(keys, 120_000L))
    }

    @Test
    fun `不同笔互不影响`() {
        val dedup = AutoBookDedup()
        dedup.markConfirmed(listOf(key("sum:alipay:0:3.0", 2 * 60 * 1000L)), 0L)
        assertTrue(dedup.shouldShow(listOf(key("sum:alipay:0:5.0", 2 * 60 * 1000L)), 1_000L))
    }

    @Test
    fun `超出容量后淘汰最旧的一条`() {
        val dedup = AutoBookDedup(capacity = 2)
        dedup.markConfirmed(listOf(key("a")), 0L)
        dedup.markConfirmed(listOf(key("b")), 0L)
        dedup.markConfirmed(listOf(key("c")), 0L)

        assertEquals(2, dedup.size())
        assertTrue("最旧的 a 应被淘汰", dedup.shouldShow(listOf(key("a")), 1_000L))
        assertFalse(dedup.shouldShow(listOf(key("c")), 1_000L))
    }

    @Test
    fun `清理后不再拦截`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("k"))
        dedup.markDismissed(keys, 0L)
        assertFalse(dedup.shouldShow(keys, 1_000L))

        dedup.clear()
        assertTrue(dedup.shouldShow(keys, 1_000L))
    }
}