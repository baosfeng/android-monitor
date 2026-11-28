package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * s03「命中方式 + 跳过耗时」（锚点 s03-breakdown）的派生逻辑。
 *
 * 锁定两条产品判据：① 命中方式必须按真实 selector 归类计数，不能在前端写死 7/2/1；
 * ② 没有 cost_ms 样本时耗时段返回 null 由调用方整段不渲染，不留「中位 — ms」占位。
 */
class AdBreakdownTest {

    private fun record(selector: String, costMs: Long? = null) = SkipRecord(
        appName = "测试应用",
        packageName = "com.example.test",
        timestampMs = 0L,
        selector = selector,
        summary = "开屏广告",
        costMs = costMs,
    )

    // ── skipMethodCounts：三档归类 ────────────────────────────────────────

    @Test
    fun `按选择器归类并分别计数`() {
        val counts = skipMethodCounts(
            listOf(
                record("#tv_splash_skip"),
                record("#tv_splash_skip"),
                record("坐标 540,1200"),
                record("无障碍 事件流"),
            ),
        )
        assertEquals(2, counts.rule)
        assertEquals(1, counts.coord)
        assertEquals(1, counts.a11y)
        assertEquals(4, counts.total)
    }

    @Test
    fun `没有记录时三档都是零`() {
        val counts = skipMethodCounts(emptyList())
        assertEquals(0, counts.rule)
        assertEquals(0, counts.coord)
        assertEquals(0, counts.a11y)
    }

    // ── skipCostBuckets：4 桶边界 ─────────────────────────────────────────

    @Test
    fun `桶边界左闭右开且四百归入 200-400`() {
        val buckets = skipCostBuckets(
            listOf(
                record("a", 0L),
                record("a", 99L),
                record("a", 100L),
                record("a", 199L),
                record("a", 200L),
                record("a", 400L),
                record("a", 401L),
            ),
        )
        assertEquals(listOf(2, 2, 2, 1), buckets)
    }

    @Test
    fun `缺少 cost_ms 的记录不参与分桶`() {
        assertEquals(listOf(0, 0, 0, 0), skipCostBuckets(listOf(record("a"))))
    }

    // ── skipCostSummary：中位 / 最慢 ─────────────────────────────────────

    @Test
    fun `奇数样本取正中`() {
        val summary = skipCostSummary(
            listOf(record("a", 512L), record("a", 128L), record("a", 64L)),
        )
        assertEquals(128L, summary?.medianMs)
        assertEquals(512L, summary?.maxMs)
    }

    @Test
    fun `偶数样本取中间两数的均值`() {
        val summary = skipCostSummary(
            listOf(record("a", 100L), record("a", 201L), record("a", 300L), record("a", 401L)),
        )
        // (201 + 300) / 2 = 250.5 → 251（四舍五入到整数毫秒）
        assertEquals(251L, summary?.medianMs)
        assertEquals(401L, summary?.maxMs)
    }

    @Test
    fun `没有耗时样本时返回空由调用方整段不渲染`() {
        assertNull(skipCostSummary(listOf(record("a"), record("b"))))
        assertNull(skipCostSummary(emptyList()))
    }

    @Test
    fun `四个桶的轴标签与原型逐字一致`() {
        assertEquals(listOf("<100", "100-200", "200-400", ">400"), SKIP_COST_BUCKET_LABELS)
    }
}
