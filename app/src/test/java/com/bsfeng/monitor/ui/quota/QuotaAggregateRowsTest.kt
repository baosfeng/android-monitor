package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 映射与灰行判定：`ProviderState` → 聚合输入 → 榜单 / 灰行。
 *
 * 这里钉的是「哪个账号进榜、哪个进灰行」。写错了不会崩，只会让用户看到一份错的紧急度排序，
 * 或某个账号凭空消失 —— 真机上最难发现的那类 bug。
 *
 * 一行 = 一个账号实例；单账号用例走 [AggregateFixtures.instanceOf] 的默认 id（== 类型 id）。
 */
class QuotaAggregateRowsTest {

    // ───────────────── 映射：ProviderState → 聚合输入 ─────────────────

    @Test
    fun `映射透传凭据与拉取元数据`() {
        val provider = AggregateFixtures.fakeProvider("opencode-go", "opencode-go")
        val state = AggregateFixtures.stateOf(
            instanceId = "opencode-go",
            items = listOf(AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12")),
            balanceConfigured = true,
            errorMessage = "网络错误",
            stale = true,
            fetchedAtEpochSecond = 1_760_000_000L,
        )

        val input = AggregateFixtures.inputOf(provider, state)

        assertEquals("opencode-go", input.instanceId)
        assertEquals("opencode-go", input.providerTypeId)
        assertEquals("opencode-go", input.displayName)
        assertEquals(1, input.items.size)
        assertTrue(input.balanceConfigured)
        assertEquals("网络错误", input.errorMessage)
        assertTrue(input.stale)
        assertEquals(1_760_000_000L, input.fetchedAtEpochSecond)
    }

    @Test
    fun `没有 state 时按未配置处理`() {
        val input = AggregateFixtures.inputOf(AggregateFixtures.fakeProvider("deepseek", "DeepSeek"), null)

        assertTrue(input.items.isEmpty())
        assertEquals(false, input.balanceConfigured)
        assertNull(input.errorMessage)
        assertEquals(0L, input.fetchedAtEpochSecond)
    }

    // ───────────────── 灰行：不参与聚合的账号 ─────────────────

    @Test
    fun `纯余额型进灰行且不算进榜单`() {
        val deepseek = AggregateFixtures.fakeProvider("deepseek", "DeepSeek")
        val states = mapOf(
            "deepseek" to AggregateFixtures.stateOf(
                instanceId = "deepseek",
                items = listOf(AggregateFixtures.moneyItem("balance:cny", "CNY 余额", "72.50")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 1_000L,
            ),
        )

        val snapshot = AggregateFixtures.snapshotOf(listOf(deepseek), states)

        assertTrue(snapshot.rows.isEmpty())
        assertEquals(1, snapshot.excluded.size)
        assertEquals("deepseek", snapshot.excluded.single().instanceId)
        assertEquals("余额型 · 无总量，算不出百分比", snapshot.excluded.single().note)
    }

    @Test
    fun `未配置凭据的账号既不进榜也不进灰行`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "内含额度", "40")),
                balanceConfigured = false,
            ),
        )

        val snapshot = AggregateFixtures.snapshotOf(listOf(provider), states)

        assertTrue(snapshot.rows.isEmpty())
        assertTrue(snapshot.excluded.isEmpty())
    }

    @Test
    fun `有配置但没有任何额度项时不进灰行`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf("ollama" to AggregateFixtures.stateOf("ollama", balanceConfigured = true))

        val snapshot = AggregateFixtures.snapshotOf(listOf(provider), states)

        assertTrue(snapshot.excluded.isEmpty())
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test
    fun `金额型但有总额时算得出百分比进榜单不进灰行`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(AggregateFixtures.moneyItemWithTotal("included", "内含额度", "8", "20")),
                balanceConfigured = true,
            ),
        )

        val snapshot = AggregateFixtures.snapshotOf(listOf(ollama), states)

        assertEquals(1, snapshot.rows.size)
        assertTrue(snapshot.excluded.isEmpty())
        assertEquals(0.4f, snapshot.rows.single().primaryFraction, 0.0001f)
    }

    @Test
    fun `同一账号既有百分比项又有纯余额项时仍进榜单`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(
                    AggregateFixtures.percentItem("included", "每周额度", "40"),
                    AggregateFixtures.moneyItem("purchased", "已购余额", "5.00"),
                ),
                balanceConfigured = true,
            ),
        )

        val snapshot = AggregateFixtures.snapshotOf(listOf(provider), states)

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
            "opencode-go" to AggregateFixtures.stateOf(
                instanceId = "opencode-go",
                items = listOf(AggregateFixtures.percentItem("window:rolling", "5 小时额度", "12")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 500L,
            ),
            "open-design" to AggregateFixtures.stateOf(
                instanceId = "open-design",
                items = listOf(AggregateFixtures.percentItem("window:month", "每月额度", "34")),
                balanceConfigured = true,
                fetchedAtEpochSecond = 900L,
            ),
        )

        // 输入顺序反过来：排序由聚合器决定，不依赖注册顺序
        val snapshot = AggregateFixtures.snapshotOf(listOf(b, a), states)

        assertEquals(listOf("opencode-go", "open-design"), snapshot.rows.map { it.instanceId })
        assertEquals(listOf(1, 2), snapshot.rows.map { it.rank })
        assertEquals("opencode-go", snapshot.leader?.instanceId)
        // 更新时间取各家最新（900），否则切账号时这行会往回跳
        assertEquals(900L, snapshot.latestFetchedAtEpochSecond)
    }

    @Test
    fun `失败但有旧数据的账号照常排序且标记 stale`() {
        val provider = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "40")),
                balanceConfigured = true,
                errorMessage = "查询过于频繁，稍后重试",
                stale = true,
                fetchedAtEpochSecond = 1_000L,
            ),
        )

        val row = AggregateFixtures.snapshotOf(listOf(provider), states).rows.single()

        assertTrue(row.stale)
        assertEquals("查询过于频繁，稍后重试", row.errorMessage)
    }

    // ───────────────── 同类型多账号 ─────────────────

    @Test
    fun `同类型两个账号各出一行且名字可分辨`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val instances = listOf(
            AggregateFixtures.instanceOf("ollama"),
            AggregateFixtures.instanceOf("ollama", seq = 2),
        )
        val states = mapOf(
            "ollama" to AggregateFixtures.stateOf(
                instanceId = "ollama",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "40")),
                balanceConfigured = true,
            ),
            "ollama#2" to AggregateFixtures.stateOf(
                instanceId = "ollama#2",
                items = listOf(AggregateFixtures.percentItem("included", "每周额度", "12")),
                balanceConfigured = true,
            ),
        )

        val snapshot = buildAggregateSnapshot(instances, listOf(ollama), states)

        assertEquals(2, snapshot.rows.size)
        assertEquals(listOf("ollama#2", "ollama"), snapshot.rows.map { it.instanceId })
        // 两行的名字必须不同：榜单上要能一眼分清是谁
        assertEquals(2, snapshot.rows.map { it.displayName }.distinct().size)
        assertEquals("Ollama · 账号 2", snapshot.rows.first().displayName)
    }

    @Test
    fun `同比例时榜单顺序与输入顺序无关`() {
        val ollama = AggregateFixtures.fakeProvider("ollama", "Ollama")
        val instances = listOf(
            AggregateFixtures.instanceOf("ollama"),
            AggregateFixtures.instanceOf("ollama", seq = 2),
        )
        fun state(id: String) = AggregateFixtures.stateOf(
            instanceId = id,
            items = listOf(AggregateFixtures.percentItem("included", "每周额度", "20")),
            balanceConfigured = true,
        )
        val states = mapOf("ollama" to state("ollama"), "ollama#2" to state("ollama#2"))

        val forward = buildAggregateSnapshot(instances, listOf(ollama), states)
        val reversed = buildAggregateSnapshot(instances.reversed(), listOf(ollama), states)

        assertEquals(forward.rows.map { it.instanceId }, reversed.rows.map { it.instanceId })
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
