package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.ui.quota.QuotaAggregateStatus
import com.bsfeng.monitor.ui.quota.buildAggregateSnapshot
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断开一家之后，聚合榜与设置页必须同时「看见」这件事（纯函数层，不组合 Compose）。
 *
 * 只测设置页是不够的：榜单与 chip 绿点读的是 `ProviderState`，而它由数据层写回。
 * 这里直接拿 [QuotaRepository.clearCredentials] 之后的状态去跑聚合入口，
 * 钉住「不再进榜、也不再被当成灰行」这两件事。
 */
class QuotaClearAggregateFallbackTest {

    /** 清除后的状态：凭据没了、数据也清了（与 [QuotaRepository.clearCredentials] 一致）。 */
    private fun clearedState(providerId: String) = QuotaRepository.ProviderState(
        providerId = providerId,
        items = emptyList(),
        usage = null,
        fetchedAtEpochSecond = 0L,
        stale = false,
        balanceConfigured = false,
        usageConfigured = false,
    )

    @Test
    fun `断开后该家从榜单消失且不占灰行`() {
        val percentProvider = FakeQuotaProvider(id = "opencode-go", displayName = "OpenCode Go")
        val snapshot = buildAggregateSnapshot(
            providers = listOf(percentProvider),
            states = mapOf(percentProvider.id to clearedState(percentProvider.id)),
        )

        assertTrue("断开后不能还排在榜上", snapshot.rows.isEmpty())
        assertTrue("未配置不是「配了但算不出百分比」，不该占灰行", snapshot.excluded.isEmpty())
        assertEquals("不该再算进已配置家数", 0, snapshot.configuredCount)
        assertEquals(
            "一家都没配 → 空态引导去配置",
            QuotaAggregateStatus.NotConfigured,
            snapshot.status,
        )
    }

    @Test
    fun `断开一家不影响其他家的位次口径`() {
        val cleared = FakeQuotaProvider(id = "ollama", displayName = "Ollama")
        val alive = FakeQuotaProvider(id = "opencode-go", displayName = "OpenCode Go")
        val aliveState = QuotaRepository.ProviderState(
            providerId = alive.id,
            items = listOf(sampleItem(remaining = "12")),
            balanceConfigured = true,
            fetchedAtEpochSecond = 1_700_000_000L,
        )
        val snapshot = buildAggregateSnapshot(
            providers = listOf(cleared, alive),
            states = mapOf(
                cleared.id to clearedState(cleared.id),
                alive.id to aliveState,
            ),
        )

        assertEquals("只剩还连着的那家", 1, snapshot.rows.size)
        assertEquals(alive.id, snapshot.rows.first().providerId)
        assertEquals("只剩一家时位次仍是 1", 1, snapshot.rows.first().rank)
        assertEquals(1, snapshot.configuredCount)
    }

    @Test
    fun `纯余额型断开后也不留在灰行里`() {
        // 灰行判据要求 balanceConfigured：断开后连灰行都不该有，只剩「未配置」
        val moneyOnly = FakeQuotaProvider(
            id = "deepseek",
            displayName = "DeepSeek",
            usageCredentialKind = null,
            items = listOf(
                com.bsfeng.monitor.domain.quota.QuotaItem(
                    id = "balance:cny",
                    title = "余额",
                    kind = QuotaKind.MONEY,
                    remaining = BigDecimal("12.34"),
                    unit = QuotaUnit.CNY,
                ),
            ),
        )
        val snapshot = buildAggregateSnapshot(
            providers = listOf(moneyOnly),
            states = mapOf(moneyOnly.id to clearedState(moneyOnly.id)),
        )

        assertTrue(snapshot.rows.isEmpty())
        assertTrue("断开后不该还显示「余额型 · 无总量」的灰行", snapshot.excluded.isEmpty())
        assertFalse("不该有失败提示：断开不是查询失败", snapshot.failureMessage != null)
    }
}
