package com.bsfeng.monitor.domain.quota

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 额度阈值提醒的判定口径：五档命中、跨档只报最低档、周期重置、余额型单档。 */
class QuotaAlertEvaluatorTest {

    private fun percentItem(
        id: String = "session",
        remaining: Double,
        resetAt: Long? = 1_000L,
    ) = QuotaItem(
        id = id,
        title = "会话",
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal.valueOf(remaining),
        unit = QuotaUnit.PERCENT,
        resetsAtEpochSecond = resetAt,
    )

    private fun balanceItem(
        id: String = "balance:cny",
        remaining: String = "42.00",
        unit: QuotaUnit = QuotaUnit.CNY,
    ) = QuotaItem(
        id = id,
        title = "余额",
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        total = null,
        unit = unit,
        primary = true,
    )

    // ───────────────────────────── 阈值命中 ─────────────────────────────

    @Test
    fun `剩余百分之六不命中任何档`() {
        assertNull(QuotaAlertEvaluator.reachedThreshold(0.06f))
    }

    @Test
    fun `剩余四点九命中五档`() {
        assertEquals(5, QuotaAlertEvaluator.reachedThreshold(0.049f))
    }

    @Test
    fun `剩余三点七命中四档`() {
        assertEquals(4, QuotaAlertEvaluator.reachedThreshold(0.037f))
    }

    @Test
    fun `剩余零点五命中一档`() {
        assertEquals(1, QuotaAlertEvaluator.reachedThreshold(0.005f))
    }

    @Test
    fun `零剩余命中一档`() {
        assertEquals(1, QuotaAlertEvaluator.reachedThreshold(0f))
    }

    // ───────────────────────────── 跨档与去重 ─────────────────────────────

    @Test
    fun `一次跨多档只提醒最低档`() {
        // 6% 直接掉到 3%：命中 4 档（不是 5/4 各报一次）
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        assertEquals(1, result.alerts.size)
        assertEquals(3, result.alerts.first().threshold)
        assertEquals(3, result.alerts.first().remainingPercent)
    }

    @Test
    fun `同一周期同一档不重复提醒`() {
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val second = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.5)),
            state = first.state,
        )
        assertTrue(second.alerts.isEmpty())
    }

    @Test
    fun `跌破更低档时再提醒一次`() {
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val second = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 1.5)),
            state = first.state,
        )
        assertEquals(1, second.alerts.size)
        assertEquals(2, second.alerts.first().threshold)
    }

    @Test
    fun `余额回升到五以上后清空记录`() {
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val recovered = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 40.0)),
            state = first.state,
        )
        assertTrue(recovered.alerts.isEmpty())
        assertEquals(0, recovered.state.getValue("ollama.session").notifiedThreshold)
    }

    @Test
    fun `周期变化后重新计数`() {
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0, resetAt = 1_000L)),
            state = emptyMap(),
        )
        val nextCycle = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0, resetAt = 2_000L)),
            state = first.state,
        )
        assertEquals(1, nextCycle.alerts.size)
        assertEquals(3, nextCycle.alerts.first().threshold)
    }

    // ───────────────────────────── 余额型 ─────────────────────────────

    @Test
    fun `余额低于阈值提醒一次`() {
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "42.00")),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        assertEquals(1, result.alerts.size)
        val alert = result.alerts.first()
        assertEquals(QuotaAlertEvaluator.LOW_BALANCE_THRESHOLD, alert.threshold)
        assertTrue(alert.bodyText(QuotaUnit.CNY).contains("42"))
    }

    @Test
    fun `余额充足不提醒`() {
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "120.00")),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun `余额充值回升后重置提醒标记`() {
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "42.00")),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        val topUp = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "200.00")),
            state = first.state,
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        assertTrue(topUp.alerts.isEmpty())
        assertEquals(false, topUp.state.getValue("deepseek.balance:cny").lowBalanceNotified)
    }

    @Test
    fun `币种没有配置阈值时不提醒`() {
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "3.00", unit = QuotaUnit.USD)),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        assertTrue(result.alerts.isEmpty())
    }

    // ───────────────────────────── 混合 ─────────────────────────────

    @Test
    fun `金额型但带总额时按比例判断`() {
        val item = QuotaItem(
            id = "included",
            title = "内含额度",
            kind = QuotaKind.MONEY,
            remaining = BigDecimal("2.00"),
            total = BigDecimal("100.00"),
            unit = QuotaUnit.USD,
        )
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(item),
            state = emptyMap(),
        )
        assertEquals(1, result.alerts.size)
        assertEquals(2, result.alerts.first().threshold)
    }

    @Test
    fun `没有总额的金额型不会被当成百分比`() {
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "0.50")),
            state = emptyMap(),
            lowBalanceThresholds = emptyMap(),
        )
        assertTrue(result.alerts.isEmpty())
    }

    // ───────────────────── 同类型多账号：状态键隔离 ─────────────────────

    @Test
    fun `同类型两个账号的预警状态互不覆盖`() {
        // 两个账号各有一条 session 额度，剩余都掉到 3%：状态键若按类型 id 拼，
        // 第二个账号会把第一个的「已提醒档位」当成自己的，于是只提醒一次。
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val second = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama#2",
            items = listOf(percentItem(remaining = 3.0)),
            state = first.state,
        )

        assertEquals("第一个账号必须提醒", 1, first.alerts.size)
        assertEquals("第二个账号也必须提醒", 1, second.alerts.size)
        assertEquals("ollama", first.alerts.first().instanceId)
        assertEquals("ollama#2", second.alerts.first().instanceId)
        // 状态键按实例隔离：两份都要在，且键不同
        assertTrue("ollama.session" in second.state)
        assertTrue("ollama#2.session" in second.state)
        assertEquals(setOf("ollama.session", "ollama#2.session"), second.state.keys)
    }

    @Test
    fun `预警状态键按实例与额度项拼接`() {
        assertEquals("ollama#2.session", alertStateKey("ollama#2", "session"))
    }

    @Test
    fun `同一账号重复评估仍然不重复提醒（实例键不破坏去重）`() {
        val first = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama#2",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val second = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama#2",
            items = listOf(percentItem(remaining = 3.2)),
            state = first.state,
        )
        assertTrue(second.alerts.isEmpty())
    }

    // ───────────────────── 已恢复（本轮新增） ─────────────────────

    @Test
    fun `同一周期回升到阈值以上产出一条已恢复`() {
        val low = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val back = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 96.0)),
            state = low.state,
        )
        assertEquals("本轮不降档", 0, back.alerts.size)
        assertEquals(1, back.recoveries.size)
        val recovery = back.recoveries.first()
        assertEquals("ollama.session", recovery.key)
        assertEquals("ollama", recovery.instanceId)
        assertEquals(3, recovery.fromPercent)
        assertEquals(96, recovery.toPercent)
        assertEquals(0, back.state.getValue("ollama.session").notifiedThreshold)
    }

    @Test
    fun `周期切换不报已恢复`() {
        // 新周期天然 100%：报「已恢复」是噪音，只静默清零
        val low = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0, resetAt = 1_000L)),
            state = emptyMap(),
        )
        val next = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 100.0, resetAt = 2_000L)),
            state = low.state,
        )
        assertEquals(0, next.recoveries.size)
        // 新周期的旧状态不被继承：再降到 3% 时必须重新提醒（否则等于「上一周期的档位还在生效」）
        val lowAgain = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0, resetAt = 2_000L)),
            state = next.state,
        )
        assertEquals(1, lowAgain.alerts.size)
    }

    @Test
    fun `从未提醒过时回升不报已恢复`() {
        val result = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 80.0)),
            state = emptyMap(),
        )
        assertEquals(0, result.alerts.size)
        assertEquals(0, result.recoveries.size)
    }

    @Test
    fun `刚好卡在五档上沿不算恢复`() {
        // 5.0% 仍命中 5 档（与 reachedThreshold 同一口径），只有 >5% 才算恢复
        val low = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val atEdge = QuotaAlertEvaluator.evaluate(
            instanceId = "ollama",
            items = listOf(percentItem(remaining = 5.0)),
            state = low.state,
        )
        assertEquals(0, atEdge.recoveries.size)
    }

    @Test
    fun `余额型回升也产出已恢复并带上金额与币种`() {
        val low = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "42.00")),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        val topUp = QuotaAlertEvaluator.evaluate(
            instanceId = "deepseek",
            items = listOf(balanceItem(remaining = "200.00")),
            state = low.state,
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        assertEquals(0, topUp.alerts.size)
        assertEquals(1, topUp.recoveries.size)
        val recovery = topUp.recoveries.first()
        assertEquals(0, recovery.fromPercent)
        assertEquals("200.00", recovery.remainingAmount?.toPlainString())
        assertEquals(QuotaUnit.CNY, recovery.unit)
        assertEquals(false, topUp.state.getValue("deepseek.balance:cny").lowBalanceNotified)
    }

    @Test
    fun `多账号的已恢复事件互不串号`() {
        // A、B 各提醒过一次，随后各自回升：两条恢复各带自己的实例键
        val aLow = QuotaAlertEvaluator.evaluate("ollama", listOf(percentItem(remaining = 3.0)), emptyMap())
        val bLow = QuotaAlertEvaluator.evaluate("ollama#2", listOf(percentItem(remaining = 3.0)), aLow.state)
        val aBack = QuotaAlertEvaluator.evaluate("ollama", listOf(percentItem(remaining = 96.0)), bLow.state)
        val bBack = QuotaAlertEvaluator.evaluate("ollama#2", listOf(percentItem(remaining = 80.0)), aBack.state)

        assertEquals(1, aBack.recoveries.size)
        assertEquals("ollama.session", aBack.recoveries.first().key)
        assertEquals(1, bBack.recoveries.size)
        assertEquals("ollama#2.session", bBack.recoveries.first().key)
        assertEquals("ollama#2", bBack.recoveries.first().instanceId)
    }
}

