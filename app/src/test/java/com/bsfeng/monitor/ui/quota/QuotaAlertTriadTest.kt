package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaAlertEvaluator
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 三态推导的口径：恢复行来自事件、低/耗尽来自快照、两行互斥、空态不占位。
 *
 * 走 Robolectric 是因为恢复事件仓库 [QuotaAlertTriadStore] 读写 SharedPreferences；
 * 纯函数用例本身不依赖沙箱，放在同一个类里免得再引一层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaAlertTriadTest {

    private val now = 1_766_000_000L
    private val day = 86_400L

    private fun percentItem(
        id: String = "session",
        remaining: String,
        title: String = "会话",
        period: QuotaPeriod? = null,
    ) = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal(remaining),
        unit = QuotaUnit.PERCENT,
        period = period,
        resetsAtEpochSecond = period?.endEpochSecond,
    )

    private fun state(
        instanceId: String = "ollama",
        items: List<QuotaItem>,
        fetchedAt: Long = now,
    ) = QuotaRepository.ProviderState(instanceId = instanceId, items = items, fetchedAtEpochSecond = fetchedAt)

    private fun triadOf(
        states: Map<String, QuotaRepository.ProviderState>,
        recoveries: List<RecoveryEvent> = emptyList(),
        instances: List<QuotaInstance> = listOf(QuotaInstance("ollama", "ollama")),
        thresholds: Map<QuotaUnit, BigDecimal> = emptyMap(),
    ) = buildAlertTriad(
        states = states,
        instances = instances,
        recoveries = recoveries,
        lowBalanceThresholds = thresholds,
        providerNameOf = { typeId -> if (typeId == "ollama") "Ollama" else typeId },
        nowEpochSecond = now,
    )

    // ───────────────────────────── 实时两态 ─────────────────────────────

    @Test
    fun `剩余十四百分比推导为快见底`() {
        val triad = triadOf(mapOf("ollama" to state(items = listOf(percentItem(remaining = "14")))))
        assertEquals(1, triad.rows.size)
        val row = triad.rows.first()
        assertEquals(QuotaAlertTriadKind.LOW, row.kind)
        assertEquals("14%", row.fractionText)
        assertEquals(0.14f, row.fraction, 1e-4f)
    }

    /**
     * 产品口径「≤20% 转黄」优先于原型三态区的示例值（那里写的是 34%，属演示数据的笔误）：
     * 34% 还剩一成半，不该和「快见底」同屏。
     */
    @Test
    fun `三十四百分比不在三态内`() {
        val triad = triadOf(mapOf("ollama" to state(items = listOf(percentItem(remaining = "34")))))
        assertTrue(triad.isEmpty)
    }

    @Test
    fun `零剩余推导为已耗尽并给两条出路`() {
        val triad = triadOf(mapOf("ollama" to state(items = listOf(percentItem(remaining = "0")))))
        val row = triad.rows.first()
        assertEquals(QuotaAlertTriadKind.DEAD, row.kind)
        assertTrue("文案要给出路", row.detailText.contains("切主号"))
    }

    @Test
    fun `高于二十百分比不进入三态`() {
        val triad = triadOf(mapOf("ollama" to state(items = listOf(percentItem(remaining = "64")))))
        assertTrue(triad.isEmpty)
    }

    @Test
    fun `余额型按阈值进快见底并显示金额`() {
        val balance = QuotaItem(
            id = "balance:cny",
            title = "余额",
            kind = QuotaKind.MONEY,
            remaining = BigDecimal("8.60"),
            unit = QuotaUnit.CNY,
        )
        val triad = triadOf(
            states = mapOf("ollama" to state(items = listOf(balance))),
            thresholds = mapOf(QuotaUnit.CNY to BigDecimal("50")),
        )
        val row = triad.rows.first()
        assertEquals(QuotaAlertTriadKind.LOW, row.kind)
        assertEquals("¥8.60", row.fractionText)
        assertEquals("余额", row.itemTitle)
    }

    // ───────────────────────────── 恢复行 ─────────────────────────────

    @Test
    fun `恢复事件推导为已恢复并带上恢复前比例`() {
        val triad = triadOf(
            states = mapOf("ollama" to state(items = listOf(percentItem(remaining = "96")))),
            recoveries = listOf(
                RecoveryEvent(
                    instanceId = "ollama",
                    itemId = "session",
                    itemTitle = "会话",
                    fromPercent = 4,
                    toPercent = 96,
                    occurredAtEpochSecond = now - 22 * 60,
                ),
            ),
        )
        val row = triad.rows.first()
        assertEquals(QuotaAlertTriadKind.RECOVERED, row.kind)
        assertEquals(0.04f, row.recoveredFrom!!, 1e-4f)
        assertEquals("0.96", "%.2f".format(row.fraction))
        assertTrue(alertTriadDetail(row, now).startsWith("22 分钟前 "))
    }

    @Test
    fun `恢复后又跌破阈值时丢弃恢复行避免自相矛盾`() {
        val triad = triadOf(
            states = mapOf("ollama" to state(items = listOf(percentItem(remaining = "12")))),
            recoveries = listOf(
                RecoveryEvent("ollama", "session", "会话", 4, 96, now - 600),
            ),
        )
        assertEquals(1, triad.rows.size)
        assertEquals(QuotaAlertTriadKind.LOW, triad.rows.first().kind)
    }

    @Test
    fun `三态都有时按恢复低耗尽排序且每态至多两行`() {
        val triad = triadOf(
            states = mapOf(
                "ollama" to state(items = listOf(percentItem("s1", "12"), percentItem("s2", "34"), percentItem("s3", "0"))),
            ),
            recoveries = listOf(RecoveryEvent("ollama", "s4", "会话", 3, 96, now - 60)),
        )
        assertEquals(3, triad.rows.size)
        assertEquals(QuotaAlertTriadKind.RECOVERED, triad.rows[0].kind)
        assertEquals(QuotaAlertTriadKind.LOW, triad.rows[1].kind)
        assertEquals(QuotaAlertTriadKind.DEAD, triad.rows[2].kind)
    }

    // ───────────────────────────── 文案 / 估算 ─────────────────────────────

    @Test
    fun `相对时间按分钟小时天分档`() {
        assertEquals("刚刚", alertAgoText(now, now))
        assertEquals("22 分钟前", alertAgoText(now - 22 * 60, now))
        assertEquals("3 小时前", alertAgoText(now - 3 * 3_600, now))
        assertEquals("2 天前", alertAgoText(now - 2 * day, now))
    }

    @Test
    fun `按当前速率估算耗尽时间`() {
        // 本周周期：过了 2 天、已用 66%（剩余 34%）→ 约 1 天耗尽
        val period = QuotaPeriod(startEpochSecond = now - 2 * day, endEpochSecond = now + 5 * day)
        val item = percentItem(remaining = "34", period = period)
        val text = exhaustEtaText(item, now)
        assertEquals("约 1 天耗尽", text)
    }

    @Test
    fun `撑得到重置时不预告耗尽`() {
        // 周期只剩 1 小时、剩余 80%：按当前速率撑得到重置
        val period = QuotaPeriod(startEpochSecond = now - 10 * 3_600, endEpochSecond = now + 3_600)
        val item = percentItem(remaining = "80", period = period)
        assertEquals(null, exhaustEtaText(item, now))
    }

    @Test
    fun `标题三种态各按原型拼法`() {
        fun row(kind: QuotaAlertTriadKind, fractionText: String = "34%") = QuotaAlertTriadRow(
            kind = kind,
            instanceId = "i",
            displayName = "Ollama 主号",
            itemTitle = "本周",
            fraction = 0.34f,
            fractionText = fractionText,
            detailText = "",
            occurredAtEpochSecond = now,
        )
        assertEquals("Ollama 主号 · 本周已恢复", alertTriadTitle(row(QuotaAlertTriadKind.RECOVERED)))
        assertEquals("Ollama 主号 · 快见底 34%", alertTriadTitle(row(QuotaAlertTriadKind.LOW)))
        assertEquals("Ollama 主号 · 本周已耗尽", alertTriadTitle(row(QuotaAlertTriadKind.DEAD)))
    }

    // ───────────────────────────── 恢复事件仓库 ─────────────────────────────

    @Test
    fun `恢复事件落盘后按时间倒序读回并截断到十二条`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("quota_alerts", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = QuotaAlertTriadStore(context) { 1_000L }
        store.append(
            listOf(
                QuotaAlertEvaluator.Recovery(
                    key = "ollama.session",
                    instanceId = "ollama",
                    itemTitle = "会话",
                    fromPercent = 4,
                    toPercent = 96,
                ),
            ),
        )
        val first = store.recent()
        assertEquals(1, first.size)
        assertEquals("session", first.first().itemId)
        assertEquals(4, first.first().fromPercent)
        assertEquals(96, first.first().toPercent)
        assertEquals(1_000L, first.first().occurredAtEpochSecond)

        // 余额型：金额与币种要一起留住，否则主屏只能显示「余额回升到」
        val later = QuotaAlertTriadStore(context) { 2_000L }
        later.append(
            listOf(
                QuotaAlertEvaluator.Recovery(
                    key = "deepseek.balance:cny",
                    instanceId = "deepseek",
                    itemTitle = "余额",
                    fromPercent = 0,
                    toPercent = 0,
                    remainingAmount = BigDecimal("200.00"),
                    unit = QuotaUnit.CNY,
                ),
            ),
        )
        val merged = later.recent()
        assertEquals(2, merged.size)
        assertEquals("¥200.00", merged.first().amountText)
        assertEquals(2_000L, merged.first().occurredAtEpochSecond)

        // 超过 12 条只留最近的（旧事件对「本轮恢复」没有意义）
        repeat(14) { index ->
            QuotaAlertTriadStore(context) { 3_000L + index }.append(
                listOf(QuotaAlertEvaluator.Recovery("ollama.s" + index, "ollama", "会话", 5, 90)),
            )
        }
        assertEquals(12, store.recent().size)
    }
}
