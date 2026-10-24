package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.quota.QuotaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 聚合视图的状态判定：为什么要从「没数据」拆成好几态。
 *
 * 这组用例钉的是一次真实的误报：首次进页面（请求在跑）和接口全失败时，
 * 聚合视图都画「还没有可聚合的额度 · 去配置 provider」，用户于是去重填了一遍
 * 本来正确的 Key。判定错在这里不会崩、也不会报错，只会悄悄把人推向错误的操作，
 * 所以必须由纯函数 + 单测钉住，而不是靠肉眼在真机上翻状态。
 */
class QuotaAggregateStatusTest {

    // ───────────────── 状态判定：为什么「没数据」要拆成好几态 ─────────────────

    @Test
    fun `一家都没配时是空态`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val snapshot = buildAggregateSnapshot(listOf(ollama), emptyMap())

        assertEquals(QuotaAggregateStatus.NotConfigured, snapshot.status)
        assertEquals(0, snapshot.configuredCount)
        assertNull(snapshot.failureMessage)
    }

    @Test
    fun `配置了但正在查询时是加载态而不是空态`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                balanceConfigured = true,
                loading = true,
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(ollama), states)

        // 这条就是本次修的主 bug：查询中不能再画「去配置 provider」
        assertEquals(QuotaAggregateStatus.Loading, snapshot.status)
        assertEquals(1, snapshot.awaitingDataCount)
        assertEquals(1, snapshot.configuredCount)
    }

    @Test
    fun `已配置但请求还没下发时也算加载态`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            // 进页面到 LaunchedEffect 下发请求之间的一帧：loading 还是 false、也没报错
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                balanceConfigured = true,
                loading = false,
                fetchedAtEpochSecond = 0L,
            ),
        )

        assertEquals(
            QuotaAggregateStatus.Loading,
            buildAggregateSnapshot(listOf(ollama), states).status,
        )
    }

    @Test
    fun `配置了但全部失败时是错误态并带失败原因`() {
        val a = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val b = AggregateFixtures.fakeProvider("open-design", "open-design")
        val states = mapOf(
            "opencode-go" to QuotaRepository.ProviderState(
                providerId = "opencode-go",
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
                errorMessage = "令牌已过期",
            ),
            "open-design" to QuotaRepository.ProviderState(
                providerId = "open-design",
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
                errorMessage = "令牌已过期",
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(a, b), states)

        assertEquals(QuotaAggregateStatus.AllFailed, snapshot.status)
        // 同源失败只显示一次，别让用户以为是两件不同的事
        assertEquals("令牌已过期", snapshot.failureMessage)
        assertEquals(0, snapshot.awaitingDataCount)
    }

    @Test
    fun `部分失败时仍是有数据态`() {
        val ok = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val bad = AggregateFixtures.fakeProvider("open-design", "open-design")
        val states = mapOf(
            "opencode-go" to QuotaRepository.ProviderState(
                providerId = "opencode-go",
                items = listOf(AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
            ),
            "open-design" to QuotaRepository.ProviderState(
                providerId = "open-design",
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
                errorMessage = "网络错误",
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(ok, bad), states)

        assertEquals(QuotaAggregateStatus.Content, snapshot.status)
        assertNull(snapshot.failureMessage)
        assertEquals(listOf("opencode-go"), snapshot.rows.map { it.providerId })
    }

    @Test
    fun `配了却全是余额型时单独成一态`() {
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

        // 凭据没问题、请求也成功，只是算不出百分比：不该引导「去配置」
        assertEquals(QuotaAggregateStatus.NoRankable, snapshot.status)
        assertEquals(1, snapshot.excluded.size)
    }

    @Test
    fun `有数据时状态不受已配置计数影响`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(
                providerId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "40")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
            ),
        )

        assertEquals(
            QuotaAggregateStatus.Content,
            buildAggregateSnapshot(listOf(ollama), states).status,
        )
    }

    @Test
    fun `没配凭据的家不计入已配置数`() {
        val a = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val b = AggregateFixtures.fakeProvider("deepseek", "DeepSeek")
        val states = mapOf(
            "ollama" to QuotaRepository.ProviderState(providerId = "ollama", loading = true),
            "deepseek" to QuotaRepository.ProviderState(
                providerId = "deepseek",
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
            ),
        )

        val snapshot = buildAggregateSnapshot(listOf(a, b), states)

        // 只有 deepseek 配了凭据：ollama 的 loading 不算「在等数据」
        assertEquals(1, snapshot.configuredCount)
        assertEquals(0, snapshot.awaitingDataCount)
    }

    @Test
    fun `失败原因汇总时只要有一家没报错就返回 null`() {
        assertNull(aggregateFailureMessage(listOf("网络错误", null)))
        assertNull(aggregateFailureMessage(emptyList()))
        assertNull(aggregateFailureMessage(listOf("", "  ")))
    }

    @Test
    fun `失败原因汇总去重并拼接不同原因`() {
        assertEquals("令牌已过期", aggregateFailureMessage(listOf("令牌已过期", "令牌已过期")))
        assertEquals("网络错误；令牌已过期", aggregateFailureMessage(listOf("网络错误", "令牌已过期")))
    }
}
