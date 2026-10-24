package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 额度页文案与几何口径。
 *
 * 这些函数错了不会崩，只会「金额少一位 / 柱高一样齐 / 其他行把 Top 3 吃掉」，
 * 真机上一眼看不出，所以把输出钉死。
 */
class QuotaFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `金额按币种符号加分两位`() {
        assertEquals("¥1,234.50", QuotaFormat.amount(BigDecimal("1234.5"), QuotaUnit.CNY))
        assertEquals("$72.50", QuotaFormat.amount(BigDecimal("72.5"), QuotaUnit.USD))
    }

    @Test
    fun `花费小于一元保留三位`() {
        assertEquals("$0.017", QuotaFormat.cost(BigDecimal("0.017"), QuotaUnit.USD))
        assertEquals("¥0.36", QuotaFormat.cost(BigDecimal("0.36"), QuotaUnit.CNY))
    }

    @Test
    fun `百分比去尾零`() {
        assertEquals("75%", QuotaFormat.percent(BigDecimal("75.00")))
        assertEquals("72.5%", QuotaFormat.percent(BigDecimal("72.50")))
    }

    @Test
    fun `token 过万压缩成万`() {
        assertEquals("1,024", QuotaFormat.tokens(1_024L))
        assertEquals("21.9 万", QuotaFormat.tokens(219_000L))
        assertEquals("658.9 万", QuotaFormat.tokens(6_589_000L))
        assertEquals("—", QuotaFormat.tokens(null))
    }

    @Test
    fun `时间戳按展示时区换算`() {
        val epoch = epochOf(2026, 10, 27, 7, 5)
        assertEquals("10/27", QuotaFormat.dateOf(epoch, zone))
        assertEquals("07:05", QuotaFormat.clockOf(epoch, zone))
        assertEquals("10/27 07:05", QuotaFormat.dateTimeOf(epoch, zone))
    }

    @Test
    fun `周期文案带剩余天数`() {
        val period = QuotaPeriod(
            startEpochSecond = epochOf(2026, 10, 1),
            endEpochSecond = epochOf(2026, 10, 29),
        )
        val text = QuotaFormat.resetText(period, null, epochOf(2026, 10, 11), zone)
        assertEquals("本周期剩 18 天 · 10/29 重置", text)
    }

    @Test
    fun `只有重置时刻时退化为时间文案`() {
        val text = QuotaFormat.resetText(null, epochOf(2026, 10, 1, 7), epochOf(2026, 9, 20), zone)
        assertEquals("10/1 07:00 重置", text)
        assertNull(QuotaFormat.resetText(null, null, 0L, zone))
    }

    @Test
    fun `Top 3 之外聚合成其他行`() {
        val groups = listOf(
            group("m1", 10, "0.10"),
            group("m2", 8, "0.08"),
            group("m3", 6, "0.06"),
            group("m4", 4, "0.04"),
            group("m5", 2, "0.02"),
        )
        val rows = QuotaFormat.topGroups(groups)
        assertEquals(4, rows.size)
        assertEquals("m1", rows[0].name)
        assertEquals(false, rows[0].isOther)
        assertEquals(true, rows[3].isOther)
        assertEquals("其他", rows[3].name)
        assertEquals(6, rows[3].metrics.requestCount)
        assertEquals(0, BigDecimal("0.06").compareTo(rows[3].metrics.cost))
    }

    @Test
    fun `聚合行不把缺失字段写成零`() {
        val merged = QuotaFormat.mergeMetrics(
            listOf(QuotaMetrics(requestCount = 1), QuotaMetrics(requestCount = 2)),
        )
        assertEquals(3, merged.requestCount)
        assertNull(merged.inputTokens)
        assertNull(merged.cost)
    }

    @Test
    fun `堆叠柱按最大总量归一`() {
        val bars = QuotaFormat.stackedBars(
            inputs = listOf(100L, 0L, null),
            outputs = listOf(100L, 0L, null),
        )
        assertEquals(3, bars.size)
        assertEquals(1f, bars[0].heightFraction, 0.0001f)
        assertEquals(0.5f, bars[0].inputFraction, 0.0001f)
        assertEquals(0f, bars[1].heightFraction, 0.0001f)
        assertEquals(0f, bars[2].inputFraction, 0.0001f)
    }

    @Test
    fun `轴标签降采样保留首尾`() {
        val labels = (0 until 24).map { "%02d".format(it) }
        val sampled = QuotaFormat.sampleLabels(labels, 6)
        assertEquals(6, sampled.size)
        assertEquals("00", sampled.first())
        assertEquals("23", sampled.last())
        assertEquals(labels, QuotaFormat.sampleLabels(labels, 30))
    }

    @Test
    fun `计费模式与返回结构不一致时提示`() {
        val percentItem = QuotaItem(
            id = "session",
            title = "会话",
            kind = QuotaKind.PERCENT,
            remaining = BigDecimal("75"),
            unit = QuotaUnit.PERCENT,
        )
        val moneyItem = QuotaItem(
            id = "balance",
            title = "余额",
            kind = QuotaKind.MONEY,
            remaining = BigDecimal("72.5"),
            unit = QuotaUnit.USD,
        )
        val usdMismatch = QuotaFormat.planMismatchNote(OllamaPlanMode.USD, listOf(percentItem))
        assertTrue(usdMismatch!!.contains("百分比"))
        val percentMismatch = QuotaFormat.planMismatchNote(OllamaPlanMode.PERCENT, listOf(moneyItem))
        assertTrue(percentMismatch!!.contains("金额"))
        assertNull(QuotaFormat.planMismatchNote(OllamaPlanMode.USD, listOf(moneyItem)))
        assertNull(QuotaFormat.planMismatchNote(OllamaPlanMode.USD, emptyList()))
    }

    /**
     * 计费模式偏好只属于 Ollama：别的 provider 的返回结构由各自接口固定，
     * 拿 Ollama 的选择去比对它们会凭空冒出一句「与所选不一致」。
     */
    @Test
    fun `计费模式提示只对 Ollama 生效`() {
        val percentItem = QuotaItem(
            id = "window:rolling",
            title = "5 小时额度",
            kind = QuotaKind.PERCENT,
            remaining = BigDecimal("87.7"),
            unit = QuotaUnit.PERCENT,
        )
        // 同样的「选了金额、返回百分比」，在 opencode-go / DeepSeek 下不提示
        assertNull(QuotaFormat.planMismatchNote(OllamaPlanMode.USD, listOf(percentItem), "opencode-go"))
        assertNull(QuotaFormat.planMismatchNote(OllamaPlanMode.PERCENT, listOf(percentItem), "deepseek"))
        // 显式传 ollama 才提示，与不传参的默认行为一致
        assertNotNull(QuotaFormat.planMismatchNote(OllamaPlanMode.USD, listOf(percentItem), "ollama"))
        assertNotNull(QuotaFormat.planMismatchNote(OllamaPlanMode.USD, listOf(percentItem)))
    }

    private fun group(name: String, requests: Int, cost: String) = QuotaGroup(
        name = name,
        metrics = QuotaMetrics(
            requestCount = requests,
            inputTokens = requests * 1_000L,
            outputTokens = requests * 100L,
            cost = BigDecimal(cost),
        ),
    )

    private fun epochOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond()
}
