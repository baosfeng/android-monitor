package com.bsfeng.monitor.domain.quota

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 额度聚合榜单的口径：只收能算百分比的行、按剩余升序、同值按 providerId 稳定兜底。 */
class QuotaAggregatorTest {

    private fun percentItem(
        id: String = "session",
        title: String = "会话",
        remaining: Double,
        primary: Boolean = false,
    ) = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal.valueOf(remaining),
        unit = QuotaUnit.PERCENT,
        primary = primary,
    )

    private fun amountItem(
        id: String = "included",
        remaining: String,
        total: String?,
    ) = QuotaItem(
        id = id,
        title = "内含额度",
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        total = total?.let { BigDecimal(it) },
        unit = QuotaUnit.USD,
    )

    private fun provider(
        id: String,
        items: List<QuotaItem>,
        configured: Boolean = true,
        error: String? = null,
        stale: Boolean = false,
        fetchedAt: Long = 1_000L,
    ) = ProviderAggregateInput(
        providerId = id,
        displayName = id.uppercase(),
        items = items,
        balanceConfigured = configured,
        errorMessage = error,
        stale = stale,
        fetchedAtEpochSecond = fetchedAt,
    )

    // ───────────────────────────── 排序 ─────────────────────────────

    @Test
    fun `剩余少的排前面且位次从1开始`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider("ollama", listOf(percentItem(remaining = 80.0))),
                provider("opencode-go", listOf(percentItem(remaining = 12.0))),
                provider("open-design", listOf(percentItem(remaining = 40.0))),
            ),
        )
        assertEquals(
            listOf("opencode-go", "open-design", "ollama"),
            rows.map { it.providerId },
        )
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
    }

    @Test
    fun `剩余比例相同按 providerId 字典序稳定`() {
        // 输入顺序故意与字典序相反：没有兜底时结果会随输入顺序抖动
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider("ollama", listOf(percentItem(remaining = 20.0))),
                provider("deepseek", listOf(percentItem(remaining = 20.0))),
                provider("open-design", listOf(percentItem(remaining = 20.0))),
            ),
        )
        assertEquals(
            listOf("deepseek", "ollama", "open-design"),
            rows.map { it.providerId },
        )
    }

    @Test
    fun `同输入重复聚合结果一致`() {
        val input = listOf(
            provider("ollama", listOf(percentItem(remaining = 20.0))),
            provider("deepseek", listOf(percentItem(remaining = 20.0))),
        )
        assertEquals(QuotaAggregator.aggregate(input), QuotaAggregator.aggregate(input))
    }

    // ───────────────────────────── 排除 ─────────────────────────────

    @Test
    fun `纯余额型不进榜单`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider("deepseek", listOf(amountItem(remaining = "42.00", total = null))),
                provider("ollama", listOf(percentItem(remaining = 30.0))),
            ),
        )
        assertEquals(listOf("ollama"), rows.map { it.providerId })
    }

    @Test
    fun `未配置额度凭据的 provider 被排除`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider("ollama", listOf(percentItem(remaining = 30.0)), configured = false),
                provider("open-design", listOf(percentItem(remaining = 60.0))),
            ),
        )
        assertEquals(listOf("open-design"), rows.map { it.providerId })
    }

    @Test
    fun `没有额度项的 provider 被排除`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider("ollama", emptyList()),
                provider("open-design", listOf(percentItem(remaining = 60.0))),
            ),
        )
        assertEquals(listOf("open-design"), rows.map { it.providerId })
    }

    @Test
    fun `全部被排除时返回空列表`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider("deepseek", listOf(amountItem(remaining = "0.50", total = null))),
                provider("ollama", listOf(percentItem(remaining = 5.0)), configured = false),
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(QuotaAggregator.aggregate(emptyList()).isEmpty())
    }

    // ───────────────────────────── 主指标 ─────────────────────────────

    @Test
    fun `单个 provider 正常成行并带上明细`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider(
                    "ollama",
                    listOf(percentItem(remaining = 35.0)),
                    error = "网络错误",
                    stale = true,
                    fetchedAt = 2_000L,
                ),
            ),
        )
        val row = rows.single()
        assertEquals("OLLAMA", row.displayName)
        assertEquals(1, row.rank)
        assertEquals(0.35f, row.primaryFraction, 1e-6f)
        assertTrue(row.otherItems.isEmpty())
        // 失败信息与陈旧标记要透传到行上，否则榜单看不出「这行是旧数」
        assertEquals("网络错误", row.errorMessage)
        assertTrue(row.stale)
        assertEquals(2_000L, row.fetchedAtEpochSecond)
    }

    @Test
    fun `多窗口 provider 取剩余最紧急的窗口`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider(
                    "opencode-go",
                    listOf(
                        percentItem(id = "session", title = "会话", remaining = 70.0, primary = true),
                        percentItem(id = "weekly", title = "周额度", remaining = 8.0),
                    ),
                ),
            ),
        )
        val row = rows.single()
        assertEquals("weekly", row.primaryItem.id)
        assertEquals(0.08f, row.primaryFraction, 1e-6f)
        assertEquals(listOf("session"), row.otherItems.map { it.id })
    }

    @Test
    fun `算不出百分比的项不参与主指标选择`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider(
                    "ollama",
                    listOf(
                        // 金额无总额：remainingFraction 为 null，不能因为金额小就当选
                        amountItem(id = "balance", remaining = "0.10", total = null),
                        percentItem(id = "session", remaining = 55.0),
                    ),
                ),
            ),
        )
        val row = rows.single()
        assertEquals("session", row.primaryItem.id)
        assertEquals(0.55f, row.primaryFraction, 1e-6f)
        // 摘要只收可量化项，纯余额项不出现在 otherItems
        assertTrue(row.otherItems.isEmpty())
        assertTrue(row.otherItems.none { it.id == "balance" })
    }

    @Test
    fun `总额为0的金额项不参与主指标选择`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider(
                    "open-design",
                    listOf(
                        amountItem(id = "zero", remaining = "0.00", total = "0"),
                        amountItem(id = "credits", remaining = "10.00", total = "100.00"),
                    ),
                ),
            ),
        )
        val row = rows.single()
        assertEquals("credits", row.primaryItem.id)
        assertEquals(0.10f, row.primaryFraction, 1e-6f)
    }

    @Test
    fun `同比例同一家优先取自报主项`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider(
                    "ollama",
                    listOf(
                        percentItem(id = "fallback", remaining = 50.0),
                        percentItem(id = "hero", remaining = 50.0, primary = true),
                    ),
                ),
            ),
        )
        assertEquals("hero", rows.single().primaryItem.id)
    }

    @Test
    fun `其余项按剩余升序排列`() {
        val rows = QuotaAggregator.aggregate(
            listOf(
                provider(
                    "ollama",
                    listOf(
                        percentItem(id = "a", remaining = 10.0),
                        percentItem(id = "b", remaining = 60.0),
                        percentItem(id = "c", remaining = 30.0),
                    ),
                ),
            ),
        )
        val row = rows.single()
        assertEquals("a", row.primaryItem.id)
        assertEquals(listOf("c", "b"), row.otherItems.map { it.id })
    }
}
