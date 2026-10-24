package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.quota.QuotaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 映射与灰行判定：`ProviderState` → 聚合输入 → 榜单 / 灰行。
 *
 * 这里钉的是「哪家进榜、哪家进灰行」。写错了不会崩，只会让用户看到一份错的紧急度排序，
 * 或某家凭空消失——真机上最难发现的那类 bug。
 */
class QuotaAggregateRowsTest {

    // ───────────────── 映射：ProviderState → 聚合输入 ─────────────────

    @Test
    fun `映射透传凭据与拉取元数据`() {
        val provider = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val state = QuotaRepository.ProviderState(
            providerId = "opencode-go",
            items = listOf(AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12")),
            balanceConfigured = true,
            errorMessage = "网络错误",
            stale = true,
            fetchedAtEpochSecond = 1_760_000_000L,
        )

        val input = aggregateInputOf(provider, state)

        assertEquals("opencode-go", input.providerId)
        assertEquals("opencode-go", input.displayName)
        assertEquals(1, input.items.size)
        assertTrue(input.balanceConfigured)
        assertEquals("网络错误", input.errorMessage)
        assertTrue(input.stale)
        assertEquals(1_760_000_000L, input.fetchedAtEpochSecond)
    }

    @Test
    fun `没有 state 时按未配置处理`() {
        val input = aggregateInputOf(AggregateFixtures.fakeProvider("deepseek", "DeepSeek"), null)

        assertTrue(input.items.isEmpty())
        assertEquals(false, input.balanceConfigured)
        assertNull(input.errorMessage)
        assertEquals(0L, input.fetchedAtEpochSecond)
    }

    // ───────────────── 灰行：不参与聚合的家 ─────────────────

    @Test
    fun `纯余额型进灰行且不算进榜单`() {
        val deepseek = AggregateFixtures.fakeProvider("deepseek", "DeepSeek")
        val states = mapOf(
            "deepseek" to QuotaRepository.ProviderState(
                providerId = "deepseek",
                items = listOf(AggregateFixtures.moneyItem("balance:cny", "CNY 余额", "72.50")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(deepseek), states)

        assertTrue(snapshot.rows.isEmpty())
        assertEquals(1, snapshot.excluded.size)
        assertEquals("deepseek", snapshot.excluded.single().providerId)
        assertEquals("余额型 · 无总量，算不出百分比", snapshot.excluded.single().note)
    }

    @Test
    fun `未配置凭据的家既不进榜也不进灰行`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "内含额度", "40")),
                balanceConfigured = false,
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(provider), states)

        assertTrue(snapshot.rows.isEmpty())
        assertTrue(snapshot.excluded.isEmpty())
    }

    @Test
    fun `有配置但没有任何额度项时不进灰行`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(providerId = "ollama", balanceConfigured = true),
        )

        val snapshot = buildAggregateSnapshot(listOf(provider), states)

        assertTrue(snapshot.excluded.isEmpty())
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test
    fun `金额型但有总额时算得出百分比，进榜单不进灰行`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                items = listOf(
                    AggregateFixtures.moneyItemWithTotal("included", "内含额度", "8", "20"),
                ),
                balanceConfigured = true,
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(ollama), states)

        assertEquals(1, snapshot.rows.size)
        assertTrue(snapshot.excluded.isEmpty())
        assertEquals(0.4f, snapshot.rows.single().primaryFraction, 0.0001f)
    }

    @Test
    fun `同一家既有百分比项又有纯余额项时仍进榜单`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                items = listOf(
                    AggregateFixtures.percentItem("included", "每周额度", "40"),
                    AggregateFixtures.moneyItem("purchased", "已购余额", "5.00"),
                ),
                balanceConfigured = true,
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(provider), states)

        assertEquals(1, snapshot.rows.size)
        assertTrue(snapshot.excluded.isEmpty())
        assertEquals(0.4f, snapshot.rows.single().primaryFraction, 0.0001f)
    }

    // ───────────────── 榜单：排序、位次、更新时间 ─────────────────

    @Test
    fun `榜单按剩余升序编位次且首位是最紧急的`() {
        val a = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val b = AggregateFixtures.fakeProvider("open-design", "open-design")
        val states = mapOf(
            "opencode-go" to QuotaRepository.ProviderState(
                providerId = "opencode-go",
                items = listOf(AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 500L,
            ),
            "open-design" to QuotaRepository.ProviderState(
                providerId = "open-design",
                items = listOf(AggregateFixtures.percentItem("window:month", "每月额度", "34")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 900L,
            ),
        )

        // 输入顺序反过来：排序由聚合器决定，不依赖注册顺序
        val snapshot = buildAggregateSnapshot(listOf(b, a), states)

        assertEquals(listOf("opencode-go", "open-design"), snapshot.rows.map { it.providerId })
        assertEquals(listOf(1, 2), snapshot.rows.map { it.rank })
        assertEquals("opencode-go", snapshot.leader?.providerId)
        // 更新时间取各家最新（900），否则切家时这行会往回跳
        assertEquals(900L, snapshot.latestFetchedAtEpochSecond)
    }

    @Test
    fun `失败但有旧数据的家照常排序且标记 stale`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "40")),
                balanceConfigured = true,
                errorMessage = "查询过于频繁，稍后重试",
                stale = true,
                fetchedAtEpochSecond = 1_000L,
            ),
        )

        val row = buildAggregateSnapshot(listOf(provider), states).rows.single()

        assertTrue(row.stale)
        assertEquals("查询过于频繁，稍后重试", row.errorMessage)
    }

    // ───────────────── 百分比格式化 ─────────────────

    @Test
    fun `百分比按整数分位格式化不丢精度`() {
        assertEquals("12%", percentOfFraction(0.12f))
        assertEquals("40%", percentOfFraction(0.40f))
        assertEquals("46%", percentOfFraction(0.46f))
        assertEquals("12.5%", percentOfFraction(0.125f))
        assertEquals("0%", percentOfFraction(0f))
        assertEquals("100%", percentOfFraction(1f))
    }

    @Test
    fun `百分比项取原始剩余值、金额项按比例折算`() {
        assertEquals(
            "12%",
            percentTextOf(AggregateFixtures.percentItem("a", "5 小时额度", "12")),
        )
        assertNull(percentTextOf(AggregateFixtures.moneyItem("balance:cny", "CNY 余额", "72.50")))
        assertEquals(
            "40%",
            percentTextOf(AggregateFixtures.moneyItemWithTotal("included", "内含额度", "8", "20")),
        )
    }
}
