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
            providerId = "ollama",
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
            providerId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val second = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
            items = listOf(percentItem(remaining = 3.5)),
            state = first.state,
        )
        assertTrue(second.alerts.isEmpty())
    }

    @Test
    fun `跌破更低档时再提醒一次`() {
        val first = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val second = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
            items = listOf(percentItem(remaining = 1.5)),
            state = first.state,
        )
        assertEquals(1, second.alerts.size)
        assertEquals(2, second.alerts.first().threshold)
    }

    @Test
    fun `余额回升到五以上后清空记录`() {
        val first = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
            items = listOf(percentItem(remaining = 3.0)),
            state = emptyMap(),
        )
        val recovered = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
            items = listOf(percentItem(remaining = 40.0)),
            state = first.state,
        )
        assertTrue(recovered.alerts.isEmpty())
        assertEquals(0, recovered.state.getValue("ollama.session").notifiedThreshold)
    }

    @Test
    fun `周期变化后重新计数`() {
        val first = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
            items = listOf(percentItem(remaining = 3.0, resetAt = 1_000L)),
            state = emptyMap(),
        )
        val nextCycle = QuotaAlertEvaluator.evaluate(
            providerId = "ollama",
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
            providerId = "deepseek",
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
            providerId = "deepseek",
            items = listOf(balanceItem(remaining = "120.00")),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun `余额充值回升后重置提醒标记`() {
        val first = QuotaAlertEvaluator.evaluate(
            providerId = "deepseek",
            items = listOf(balanceItem(remaining = "42.00")),
            state = emptyMap(),
            lowBalanceThresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        val topUp = QuotaAlertEvaluator.evaluate(
            providerId = "deepseek",
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
            providerId = "deepseek",
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
            providerId = "ollama",
            items = listOf(item),
            state = emptyMap(),
        )
        assertEquals(1, result.alerts.size)
        assertEquals(2, result.alerts.first().threshold)
    }

    @Test
    fun `没有总额的金额型不会被当成百分比`() {
        val result = QuotaAlertEvaluator.evaluate(
            providerId = "deepseek",
            items = listOf(balanceItem(remaining = "0.50")),
            state = emptyMap(),
            lowBalanceThresholds = emptyMap(),
        )
        assertTrue(result.alerts.isEmpty())
    }
}
