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

    /** 金额兜底指纹（非精确）：真实调用方由 [AutoBookParser.buildDedupKeys] 这样构造 */
    private fun fuzzyKey(value: String, ttlMs: Long = 2 * 60 * 1000L) =
        DedupKey(value, ttlMs, precise = false)

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
        val keys = listOf(key("order:alipay:CFP001"))
        dedup.markDismissed(keys, 0L)

        assertFalse(dedup.shouldShow(keys, 5 * 60 * 1000L))
        assertTrue(dedup.blockedReason(keys, 5 * 60 * 1000L)!!.contains("已忽略"))
        assertTrue("超过安静期后可以再弹", dedup.shouldShow(keys, 10 * 60 * 1000L))
    }

    @Test
    fun `浮窗弹过后金额模糊指纹只做 5 秒防抖`() {
        val dedup = AutoBookDedup()
        val keys = listOf(fuzzyKey("sum:wechat:0:12.3"))
        dedup.markShown(keys, 0L)

        assertFalse(dedup.shouldShow(keys, 4_000L))
        assertTrue(
            "5 秒后允许再弹 —— 下一笔同金额交易不能被静默吞掉（真机 2026-10-17）",
            dedup.shouldShow(keys, 5_001L)
        )
    }

    @Test
    fun `精确指纹的弹窗防抖仍是 30 秒`() {
        val dedup = AutoBookDedup()
        val keys = listOf(key("order:alipay:CFP001"))
        dedup.markShown(keys, 0L)

        assertFalse(dedup.shouldShow(keys, 29_000L))
        assertTrue("30 秒后允许再弹（用户没操作，可能只是浮窗被系统收起）", dedup.shouldShow(keys, 30_001L))
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
    fun `金额兜底指纹不承担长期记忆：同金额的另一笔不会被吞`() {
        // 真机回归（2026-10-16 微信）：用户忽略一笔 ¥0.01 转账后 11 秒发出 ¥0.01 红包，
        // 两者模糊指纹完全相同（sum:wechat:0:0.01），当时红包被静默吞掉、浮窗不弹。
        val dedup = AutoBookDedup()
        val transfer = listOf(fuzzyKey("sum:wechat:0:0.01"))
        dedup.markShown(transfer, 0L)
        dedup.markDismissed(transfer, 39_000L)

        assertTrue(
            "模糊指纹分不清两笔，不能让它记住「已忽略」",
            dedup.shouldShow(listOf(fuzzyKey("sum:wechat:0:0.01")), 50_000L)
        )
    }

    @Test
    fun `确认记账后模糊指纹同样不拦下一笔`() {
        val dedup = AutoBookDedup()
        val keys = listOf(fuzzyKey("sum:wechat:0:0.01"))
        dedup.markConfirmed(keys, 0L)

        assertTrue(
            "记账那一侧由落库键（含 5 分钟时间桶）兜底，模糊指纹不该拦住同金额的下一笔",
            dedup.shouldShow(listOf(fuzzyKey("sum:wechat:0:0.01")), 1_000L)
        )
    }

    @Test
    fun `模糊指纹的短暂防抖仍然生效`() {
        // 去掉长期记忆不等于去掉防抖：同一笔在弹窗后连续抛事件时不能再弹
        val dedup = AutoBookDedup()
        val keys = listOf(fuzzyKey("sum:wechat:0:0.01"))
        dedup.markShown(keys, 0L)

        assertFalse("刚弹过，5 秒内不复弹", dedup.shouldShow(keys, 4_000L))
        assertTrue("5 秒防抖窗口后放行（同金额的下一笔要能弹）", dedup.shouldShow(keys, 6_000L))
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