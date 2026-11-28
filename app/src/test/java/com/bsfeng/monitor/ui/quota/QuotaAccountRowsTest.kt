package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号卡列表的推导规则（r4 主屏的唯一口径来源）。
 *
 * 这些规则错一个，主屏就是「数字对不上 / 该折叠的没折叠 / 结论条指向错的人」，
 * 而且都不会崩 —— 只能靠纯函数用例钉住。
 */
class QuotaAccountRowsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Long = LocalDateTime.of(2026, 10, 14, 9, 41).atZone(zone).toInstant().epochSecond

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 14, hour, minute).atZone(zone).toInstant().epochSecond

    private fun day(month: Int, dayOfMonth: Int, hour: Int): Long =
        LocalDateTime.of(2026, month, dayOfMonth, hour, 0).atZone(zone).toInstant().epochSecond

    private fun pct(id: String, title: String, remaining: String, primary: Boolean = false, reset: Long? = null) =
        QuotaItem(
            id = id,
            title = title,
            kind = QuotaKind.PERCENT,
            remaining = BigDecimal(remaining),
            unit = QuotaUnit.PERCENT,
            resetsAtEpochSecond = reset,
            primary = primary,
        )

    private fun balance(remaining: String) = QuotaItem(
        id = "ds.balance",
        title = "余额",
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        total = null,
        unit = QuotaUnit.CNY,
    )

    private fun info(
        instanceId: String,
        typeId: String,
        typeName: String,
        multi: Boolean = false,
        label: String = "",
    ) = QuotaProviderInfo(
        instanceId = instanceId,
        providerTypeId = typeId,
        displayName = typeName,
        typeDisplayName = typeName,
        label = label,
        multiAccount = multi,
        supportsUsage = true,
        usageConfigured = true,
    )

    private fun state(
        id: String,
        items: List<QuotaItem> = emptyList(),
        fetchedAt: Long = now,
        error: String? = null,
        balanceConfigured: Boolean = true,
        usageConfigured: Boolean = true,
    ) = QuotaRepository.ProviderState(
        instanceId = id,
        items = items,
        fetchedAtEpochSecond = fetchedAt,
        errorMessage = error,
        balanceConfigured = balanceConfigured,
        usageConfigured = usageConfigured,
    )

    private val thresholds = mapOf(QuotaUnit.CNY to BigDecimal("50"), QuotaUnit.USD to BigDecimal("5"))

    // MARK: - 列选择

    @Test
    fun `两列取剩余最低的两个窗口`() {
        val columns = quotaAccountColumns(
            state("oc", listOf(pct("a", "5 小时", "71", primary = true), pct("b", "本周", "12"), pct("c", "本月", "46"))),
            configured = true,
            nowEpochSecond = now,
            zone = zone,
        )
        assertEquals(listOf("本周", "本月"), columns.map { it.title })
        assertEquals(listOf("12%", "46%"), columns.map { it.valueText })
    }

    @Test
    fun `同比例时主项排在前面`() {
        val columns = quotaAccountColumns(
            state("oc", listOf(pct("a", "本月", "20"), pct("b", "5 小时", "20", primary = true))),
            configured = true,
            nowEpochSecond = now,
            zone = zone,
        )
        assertEquals(listOf("5 小时", "本月"), columns.map { it.title })
    }

    @Test
    fun `纯余额型只出一列金额与更新时间`() {
        val columns = quotaAccountColumns(
            state("ds", listOf(balance("8.60")), fetchedAt = now - 8 * 60),
            configured = true,
            nowEpochSecond = now,
            zone = zone,
        )
        assertEquals(1, columns.size)
        assertEquals("余额", columns.single().title)
        assertEquals("¥8.60", columns.single().valueText)
        assertEquals("8 分钟前", columns.single().resetText)
        assertNull("余额型没有剩余比例", columns.single().fraction)
    }

    @Test
    fun `未配置只出一列占位`() {
        val columns = quotaAccountColumns(state("oc", balanceConfigured = false, usageConfigured = false), false, now, zone)
        assertEquals(listOf("未配置"), columns.map { it.title })
        assertEquals(listOf("—"), columns.map { it.valueText })
    }

    @Test
    fun `0 与 20% 分别落在危险与警告档`() {
        assertEquals(QuotaColumnState.DEAD, columnStateOf(0f))
        assertEquals(QuotaColumnState.LOW, columnStateOf(0.20f))
        assertEquals(QuotaColumnState.NORMAL, columnStateOf(0.21f))
    }

    // MARK: - 徽标 / 紧急

    @Test
    fun `耗尽压过快见底与主号`() {
        val badge = quotaAccountBadge(
            state("oc#2", listOf(pct("a", "5 小时", "0"), pct("b", "本周", "62"))),
            info("oc#2", "opencode-go", "opencode-go", multi = true),
            seq = 2,
            recovered = emptySet(),
            lowBalanceThresholds = thresholds,
        )
        assertEquals("耗尽", badge?.text)
        assertEquals(QuotaBadgeTone.DANGER, badge?.tone)
    }

    @Test
    fun `快见底按 20% 口径 34% 不算`() {
        val low = quotaAccountBadge(
            state("od", listOf(pct("a", "本月", "20"))),
            info("od", "opendesign", "open-design"),
            seq = 1, recovered = emptySet(), lowBalanceThresholds = thresholds,
        )
        assertEquals("快见底", low?.text)
        val notLow = quotaAccountBadge(
            state("od", listOf(pct("a", "本月", "34"))),
            info("od", "opendesign", "open-design", multi = true),
            seq = 1, recovered = emptySet(), lowBalanceThresholds = thresholds,
        )
        assertEquals("主号", notLow?.text)
    }

    @Test
    fun `令牌过期取自凭据类错误的用户文案`() {
        val badge = quotaAccountBadge(
            state("ds", listOf(balance("110")), error = QuotaError(QuotaErrorKind.UNAUTHORIZED).userMessage),
            info("ds", "deepseek", "DeepSeek"),
            seq = 1, recovered = emptySet(), lowBalanceThresholds = thresholds,
        )
        assertEquals("令牌过期", badge?.text)
        assertTrue("令牌过期也算需处理", badge?.urgent == true)
    }

    @Test
    fun `余额低于阈值出 danger 徽标`() {
        val badge = quotaAccountBadge(
            state("ds#2", listOf(balance("8.60"))),
            info("ds#2", "deepseek", "DeepSeek", multi = true),
            seq = 2, recovered = emptySet(), lowBalanceThresholds = thresholds,
        )
        assertEquals("低于阈值", badge?.text)
        assertEquals(QuotaBadgeTone.DANGER, badge?.tone)
        assertTrue(badge?.urgent == true)
    }

    @Test
    fun `恢复事件压过主号但不压过快见底`() {
        val recovered = quotaAccountBadge(
            state("ol", listOf(pct("a", "本周", "96"))),
            info("ol", "ollama", "Ollama"),
            seq = 1, recovered = setOf("ol"), lowBalanceThresholds = thresholds,
        )
        assertEquals("已恢复", recovered?.text)
        val lowWins = quotaAccountBadge(
            state("ol", listOf(pct("a", "本周", "12"))),
            info("ol", "ollama", "Ollama"),
            seq = 1, recovered = setOf("ol"), lowBalanceThresholds = thresholds,
        )
        assertEquals("快见底", lowWins?.text)
    }

    @Test
    fun `主号备用不是需处理状态`() {
        val badge = quotaAccountBadge(
            state("oc", listOf(pct("a", "本周", "96"))),
            info("oc", "opencode-go", "opencode-go", multi = true),
            seq = 1, recovered = emptySet(), lowBalanceThresholds = thresholds,
        )
        assertEquals("主号", badge?.text)
        assertFalse(badge?.urgent == true)
    }

    @Test
    fun `多账号未改名的身份是主号备用与账号 N`() {
        val multi = info("x", "deepseek", "DeepSeek", multi = true)
        assertEquals("主号", quotaAccountIdentity(multi, 1))
        assertEquals("备用", quotaAccountIdentity(multi, 2))
        assertEquals("账号 3", quotaAccountIdentity(multi, 3))
        assertEquals("", quotaAccountIdentity(info("y", "ollama", "Ollama"), 1))
        assertEquals("工作区 A", quotaAccountIdentity(multi.copy(label = "工作区 A"), 1))
    }

    // MARK: - 结论条 / 页头

    @Test
    fun `结论条取全局最低的那一列`() {
        val accounts = buildQuotaAccounts(
            providers = listOf(
                info("oc", "opencode-go", "opencode-go", multi = true),
                info("oc#2", "opencode-go", "opencode-go", multi = true),
            ),
            states = mapOf(
                "oc" to state("oc", listOf(pct("a", "5 小时", "12", reset = at(17, 0)))),
                "oc#2" to state("oc#2", listOf(pct("a", "5 小时", "0", reset = at(17, 0)))),
            ),
            triad = QuotaAlertTriad(),
            lowBalanceThresholds = thresholds,
            nowEpochSecond = now,
            zone = zone,
        )
        val leader = quotaLeader(accounts)
        assertEquals("opencode-go · 备用", leader?.headline)
        assertEquals("5 小时窗口", leader?.windowText)
        assertEquals("0%", leader?.valueText)
        assertEquals("今天 17:00", leader?.resetText)
    }

    @Test
    fun `一条可量化窗口都没有时结论条为空`() {
        val accounts = buildQuotaAccounts(
            providers = listOf(info("ds", "deepseek", "DeepSeek")),
            states = mapOf("ds" to state("ds", listOf(balance("110")))),
            triad = QuotaAlertTriad(),
            lowBalanceThresholds = thresholds,
            nowEpochSecond = now,
            zone = zone,
        )
        assertNull(quotaLeader(accounts))
    }

    @Test
    fun `页头计数是账号数与家数`() {
        val accounts = buildQuotaAccounts(
            providers = listOf(
                info("oc", "opencode-go", "opencode-go", multi = true),
                info("oc#2", "opencode-go", "opencode-go", multi = true),
                info("ol", "ollama", "Ollama"),
            ),
            states = emptyMap(),
            triad = QuotaAlertTriad(),
            lowBalanceThresholds = thresholds,
            nowEpochSecond = now,
            zone = zone,
        )
        assertEquals(3 to 2, quotaHeadCounts(accounts))
    }

    // MARK: - 时间文案

    @Test
    fun `重置时刻按今天明天周几与日期四档`() {
        assertEquals("今天 17:00", quotaResetText(at(17, 0), now, zone))
        assertEquals("明天 00:00", quotaResetText(day(10, 15, 0), now, zone))
        assertEquals("周一 00:00", quotaResetText(day(10, 19, 0), now, zone))
        assertEquals("12-01 00:00", quotaResetText(day(12, 1, 0), now, zone))
        assertNull(quotaResetText(null, now, zone))
    }

    @Test
    fun `周期对象也能当重置时刻的兜底`() {
        val item = pct("a", "本周", "46").copy(
            period = QuotaPeriod(startEpochSecond = day(10, 13, 0), endEpochSecond = day(10, 19, 0)),
        )
        val columns = quotaAccountColumns(state("oc", listOf(item)), true, now, zone)
        assertEquals("周一 00:00", columns.single().resetText)
    }

    @Test
    fun `页头时间戳四档与失败口径`() {
        assertEquals("尚未更新", quotaUpdatedText(0L, now))
        assertEquals("更新于刚刚", quotaUpdatedText(now - 30, now))
        assertEquals("更新于 4 分钟前", quotaUpdatedText(now - 4 * 60, now))
        assertEquals("更新于 9:41", quotaUpdatedText(at(9, 41) - 86_400, now, zone = zone))
        assertEquals("更新失败", quotaUpdatedText(0L, now, failed = true))
        // 393dp 下带前缀 + 时间会截断，失败态只报结论（时间在结论条 / 三态区）
        assertEquals("更新失败", quotaUpdatedText(now - 4 * 60, now, failed = true))
    }

    @Test
    fun `任一账号失败或数据陈旧都算更新失败`() {
        assertFalse(quotaHasFailure(mapOf("a" to state("a", listOf(pct("x", "本周", "90"))))))
        assertTrue(quotaHasFailure(mapOf("a" to state("a", error = "网络错误"))))
        assertTrue(quotaHasFailure(mapOf("a" to state("a").copy(stale = true))))
    }

    @Test
    fun `最近一次成功拉取取最大时间戳`() {
        assertEquals(
            now,
            quotaLatestFetch(
                mapOf(
                    "a" to state("a", fetchedAt = now - 3_600),
                    "b" to state("b", fetchedAt = now),
                ),
            ),
        )
        assertEquals(0L, quotaLatestFetch(emptyMap()))
    }
}
