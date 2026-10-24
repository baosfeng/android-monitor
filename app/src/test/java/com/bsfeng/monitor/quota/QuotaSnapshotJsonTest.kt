package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaSnapshot
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * 快照编解码往返（Robolectric：org.json 在纯 JVM 测试里是桩）。
 *
 * 盯住三件事：额度项与用量都能原样回来、坏数据整体返回 null（不半渲染）、空快照不写盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaSnapshotJsonTest {

    private fun sampleSnapshot(): QuotaSnapshot {
        val period = QuotaPeriod(startEpochSecond = 1_700_000_000L, endEpochSecond = 1_702_592_000L)
        return QuotaSnapshot(
            providerId = "ollama",
            items = listOf(
                QuotaItem(
                    id = "included",
                    title = "内含额度",
                    kind = QuotaKind.MONEY,
                    remaining = BigDecimal("72.50"),
                    total = BigDecimal("100.00"),
                    unit = QuotaUnit.USD,
                    period = period,
                    resetsAtEpochSecond = 1_702_592_000L,
                    primary = true,
                    detailText = "743 / 50,000 credits",
                    badge = "Go",
                ),
                QuotaItem(
                    id = "session",
                    title = "会话额度",
                    kind = QuotaKind.PERCENT,
                    remaining = BigDecimal("63.4"),
                    unit = QuotaUnit.PERCENT,
                ),
            ),
            usage = QuotaUsage(
                range = QuotaRange.LAST_24_HOURS,
                granularity = QuotaGranularity.HOUR,
                startEpochSecond = 1_700_000_000L,
                endEpochSecond = 1_700_086_400L,
                totals = QuotaMetrics(requestCount = 15, inputTokens = 106_000, cost = BigDecimal("0.017")),
                buckets = listOf(
                    QuotaBucket(
                        startEpochSecond = 1_700_000_000L,
                        endEpochSecond = 1_700_003_600L,
                        partial = true,
                        metrics = QuotaMetrics(requestCount = 3),
                    ),
                ),
                groups = listOf(QuotaGroup("glm-5.3-flash", QuotaMetrics(requestCount = 12))),
                costUnit = QuotaUnit.USD,
            ),
            fetchedAtEpochSecond = 1_700_090_000L,
        )
    }

    @Test
    fun `编解码往返保留额度项与用量`() {
        val original = sampleSnapshot()
        val decoded = QuotaSnapshotJson.decode(QuotaSnapshotJson.encode(original))

        assertEquals(original.providerId, decoded!!.providerId)
        assertEquals(original.fetchedAtEpochSecond, decoded.fetchedAtEpochSecond)
        assertEquals(2, decoded.items.size)

        val first = decoded.items[0]
        assertEquals("included", first.id)
        assertEquals(QuotaKind.MONEY, first.kind)
        assertEquals(0, first.remaining.compareTo(BigDecimal("72.50")))
        assertEquals(0, first.total!!.compareTo(BigDecimal("100.00")))
        assertEquals(1_700_000_000L, first.period!!.startEpochSecond)
        assertEquals("743 / 50,000 credits", first.detailText)
        assertEquals("Go", first.badge)
        assertTrue(first.primary)

        val usage = decoded.usage!!
        assertEquals(QuotaRange.LAST_24_HOURS, usage.range)
        assertEquals(15, usage.totals.requestCount)
        assertEquals(106_000L, usage.totals.inputTokens)
        assertEquals(1, usage.buckets.size)
        assertTrue(usage.buckets[0].partial)
        assertEquals("glm-5.3-flash", usage.groups[0].name)
        assertEquals(QuotaUnit.USD, usage.costUnit)
    }

    @Test
    fun `坏 JSON 整体返回 null 而不是半张卡`() {
        assertNull(QuotaSnapshotJson.decode("{"))
        assertNull(QuotaSnapshotJson.decode(""))
        // items 类型不对时按空列表处理，不抛异常也不丢 providerId
        val lenient = QuotaSnapshotJson.decode("""{"providerId":"x","items":"not-an-array"}""")
        assertEquals("x", lenient!!.providerId)
        assertTrue(lenient.items.isEmpty())
    }

    @Test
    fun `没有用量的快照也能往返`() {
        val snapshot = QuotaSnapshot(
            providerId = "opendesign",
            items = listOf(
                QuotaItem("window:18000", "5 小时额度", QuotaKind.PERCENT, BigDecimal("98.5"), unit = QuotaUnit.PERCENT),
            ),
            fetchedAtEpochSecond = 1_700_000_000L,
        )
        val decoded = QuotaSnapshotJson.decode(QuotaSnapshotJson.encode(snapshot))!!
        assertEquals(1, decoded.items.size)
        assertNull(decoded.usage)
    }
}
