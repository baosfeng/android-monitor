package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聚合视图的文案口径：统计行、结论条、行副文案。
 *
 * 这些字符串是页面唯一的信息出口（没有图表替它们兜底），写错了用户读到的是错的结论，
 * 而不是看到一个明显的错误。测试把成品句子整句钉死。
 */
class QuotaAggregateTextTest {

    private val zone = AggregateFixtures.zone

    @Test
    fun `统计行带账号数与更新时间`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "40")),
                balanceConfigured = true,
                fetchedAtEpochSecond = AggregateFixtures.epochOf(2026, 10, 24, 9, 41),
            ),
        )

        val text = aggregateMetaText(AggregateFixtures.snapshotOf(listOf(provider), states), zone = zone)

        // 原型 s18 页头写「更新于 9:41」（时不补零）
        assertEquals("1 个 provider 已连接 · 更新于 9:41", text)
    }

    @Test
    fun `从未更新时统计行写尚未更新`() {
        assertEquals("0 个 provider 已连接 · 尚未更新", aggregateMetaText(QuotaAggregateSnapshot(), zone = zone))
    }

    @Test
    fun `副文案并排多个窗口且主指标在最前`() {
        val provider = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val states = mapOf(
            "opencode-go" to AggregateFixtures.stateOf(
                instanceId = "opencode-go",
                items = listOf(
                    AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12", primary = true),
                    AggregateFixtures.percentItem("window:weekly", "每周额度", "46"),
                    AggregateFixtures.percentItem("window:monthly", "每月额度", "71"),
                ),
                balanceConfigured = true,
            ),
        )

        val row = AggregateFixtures.snapshotOf(listOf(provider), states).rows.single()

        assertEquals("5 小时 12% · 每周 46% · 每月 71%", aggregateSummary(row))
    }

    @Test
    fun `陈旧数据的副文案补上停在几点`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(
                    AggregateFixtures.percentItem("included", "每周额度", "40", primary = true),
                    AggregateFixtures.percentItem("session", "会话额度", "75"),
                ),
                balanceConfigured = true,
                stale = true,
                fetchedAtEpochSecond = AggregateFixtures.epochOf(2026, 10, 23, 22, 14),
            ),
        )

        val row = AggregateFixtures.snapshotOf(listOf(provider), states).rows.single()

        assertEquals("每周 40% · 会话 75%", aggregateSummary(row))
        assertEquals("每周 40% · 会话 75% · 停在 22:14", aggregateSubText(row, zone))
    }

    @Test
    fun `新鲜数据不追加停在几点`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "40")),
                balanceConfigured = true,
                fetchedAtEpochSecond = AggregateFixtures.epochOf(2026, 10, 24, 9, 41),
            ),
        )

        val row = AggregateFixtures.snapshotOf(listOf(provider), states).rows.single()

        // 只有一档时补「单窗口」（原型 .s18-agg-sub「月度 34% · 单窗口」同一口径）
        assertEquals("每周 40% · 单窗口", aggregateSubText(row, zone))
    }

    @Test
    fun `多窗口不补单窗口标记`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(
                    AggregateFixtures.percentItem("included", "每周额度", "40", primary = true),
                    AggregateFixtures.percentItem("session", "会话额度", "75"),
                ),
                balanceConfigured = true,
            ),
        )

        val row = AggregateFixtures.snapshotOf(listOf(provider), states).rows.single()

        assertEquals("每周 40% · 会话 75%", aggregateSummary(row))
    }

    @Test
    fun `结论条文案是账号名加窗口短名`() {
        val provider = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val states = mapOf(
            "opencode-go" to AggregateFixtures.stateOf(
                instanceId = "opencode-go",
                items = listOf(AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12")),
                balanceConfigured = true,
            ),
        )

        val row = AggregateFixtures.snapshotOf(listOf(provider), states).rows.single()

        assertEquals("opencode-go · 5 小时", leaderTitle(row))
        // 短名只砍「额度」后缀；整条就是「额度」时原样返回，不能变成空串
        assertEquals("5 小时额度", row.primaryItem.title)
        assertEquals("5 小时", windowShortTitle("5 小时额度"))
        assertEquals("额度", windowShortTitle("额度"))
    }

    @Test
    fun `同类型多账号时结论条带账号名`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val instances = listOf(
            AggregateFixtures.instanceOf("ollama"),
            AggregateFixtures.instanceOf("ollama", seq = 2, label = "工作"),
        )
        val states = mapOf(
            "ollama#2" to AggregateFixtures.stateOf(
                instanceId = "ollama#2",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "8")),
                balanceConfigured = true,
            ),
        )

        val row = buildAggregateSnapshot(instances, listOf(ollama), states).rows.single()

        assertEquals("Ollama · 工作 · 每周", leaderTitle(row))
    }
}
