package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.ui.components.BadgeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * s19 重设计新增纯函数的单测：状态副行 / 分组摘要 / 节奏文案 / 锚点映射。
 *
 * 这些函数决定「这一行看起来对不对」，写错了不崩、只是数字或文案悄悄变样，
 * 所以按正常 / 边界 / 异常三条各钉一条。
 */
class QuotaSettingsNewLayoutTest {

    private fun info(
        instanceId: String = "ollama",
        typeId: String = "ollama",
        multi: Boolean = false,
        balanceKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY,
    ): QuotaProviderInfo = QuotaProviderInfo(
        instanceId = instanceId,
        providerTypeId = typeId,
        displayName = if (multi) "Ollama · 账号 2" else "Ollama",
        typeDisplayName = "Ollama",
        multiAccount = multi,
        connected = false,
        balanceKind = balanceKind,
    )

    private fun state(
        id: String = "ollama",
        configured: Boolean = true,
        fetchedAt: Long = 0,
        items: List<QuotaItem> = emptyList(),
        error: String? = null,
    ): QuotaRepository.ProviderState = QuotaRepository.ProviderState(
        instanceId = id,
        items = items,
        fetchedAtEpochSecond = fetchedAt,
        balanceConfigured = configured,
        errorMessage = error,
    )

    private fun percent(title: String, remaining: String, primary: Boolean = false): QuotaItem = QuotaItem(
        id = title,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal(remaining),
        unit = QuotaUnit.PERCENT,
        primary = primary,
    )

    @Test
    fun agoText_covers_minutes_hours_days() {
        assertEquals("刚刚", agoText(30))
        assertEquals("5 分钟", agoText(300))
        assertEquals("1 小时", agoText(3600))
        assertEquals("1 小时 12 分钟", agoText(4320))
        assertEquals("1 天", agoText(90_000))
    }

    @Test
    fun connected_status_shows_kind_time_and_primary_window() {
        val text = accountStatusText(
            info = info(),
            state = state(fetchedAt = NOW - 300, items = listOf(percent("每周", "96", primary = true))),
            nowEpochSecond = NOW,
        )
        assertEquals("已连接", text.badge)
        assertEquals(BadgeTone.Success, text.tone)
        assertEquals("API Key · 5 分钟前更新 · 每周 96%", text.subtitle)
    }

    @Test
    fun unauthorized_error_reads_as_expired_token_and_keeps_last_time() {
        val text = accountStatusText(
            info = info("deepseek", "deepseek", balanceKind = QuotaCredentialKind.PLATFORM_TOKEN),
            state = state(
                id = "deepseek",
                fetchedAt = NOW - 4320,
                error = "凭据无效或已过期",
            ),
            nowEpochSecond = NOW,
        )
        assertEquals("令牌过期", text.badge)
        assertEquals(BadgeTone.Danger, text.tone)
        assertEquals("平台登录令牌 · 停在 1 小时 12 分钟前", text.subtitle)
    }

    @Test
    fun non_auth_error_is_not_reported_as_expired() {
        assertFalse(looksLikeExpiredToken("网络错误"))
        assertTrue(looksLikeExpiredToken("HTTP 401 Unauthorized"))
        val text = accountStatusText(
            info = info(),
            state = state(error = "网络错误"),
            nowEpochSecond = NOW,
        )
        assertEquals("查询失败", text.badge)
        assertEquals("API Key · 上次拉取失败", text.subtitle)
    }

    @Test
    fun unconfigured_second_account_explains_it_does_not_overwrite() {
        val text = accountStatusText(info = info(multi = true), state = null, nowEpochSecond = NOW)
        assertEquals("未配置", text.badge)
        assertEquals("添加后与主号并列，不覆盖主号凭据", text.subtitle)
        assertTrue(accountNeedsAttention(text))
    }

    @Test
    fun group_summary_counts_only_attention_rows() {
        assertEquals("4 个 · 2 个需处理", accountsGroupSummary(4, 2))
        assertEquals("3 个", accountsGroupSummary(3, 0))
    }

    @Test
    fun rhythm_text_keeps_foreground_and_background_separate() {
        val on = rhythmText(frontMinutes = 1, backgroundMinutes = 15)
        assertTrue(on.contains("前台每 1 分钟"))
        assertTrue(on.contains("后台每 15 分钟"))
        assertTrue(on.contains("推迟到 30 分钟"))
        val off = rhythmText(frontMinutes = 5, backgroundMinutes = 0)
        assertTrue(off.contains("后台已关闭"))
    }

    @Test
    fun anchor_suffix_follows_prototype_demo_accounts() {
        assertEquals("ollama", accountAnchorSuffix("ollama", 0))
        assertEquals("ollama2", accountAnchorSuffix("ollama", 1))
        assertEquals("ds1", accountAnchorSuffix("deepseek", 0))
        assertEquals("od-a", accountAnchorSuffix("opendesign", 0))
        assertEquals("od-b", accountAnchorSuffix("opendesign", 1))
        assertEquals("", accountAnchorSuffix("opencode-go", 0))
    }

    @Test
    fun anchor_mapping_is_stable_per_family() {
        assertEquals("s19-acc-ollama", quotaSettingsAnchor("acc", "ollama"))
        assertEquals("s19-panel-od-a", quotaSettingsAnchor("panel", "od-a"))
        assertEquals("s19-paste-hint3", quotaSettingsAnchor("pasteHint", "ds1"))
        assertEquals("s19-clear-ollama", quotaSettingsAnchor("clear", "ollama"))
        // 原型没有示例编号的 provider 落组件级通用锚点
        assertEquals("row.account", quotaSettingsAnchor("acc", ""))
        assertEquals("button.save", quotaSettingsAnchor("save", ""))
        assertEquals("s19-add-ollama", addAccountAnchorOf("ollama"))
        assertEquals("s19-add-od", addAccountAnchorOf("opendesign"))
        assertEquals("row.addAccount", addAccountAnchorOf("deepseek"))
        assertEquals("s19-int-5", refreshChipAnchorOf(5))
        assertEquals("s19-int-60", refreshChipAnchorOf(60))
        assertEquals("s19-int-off", refreshChipAnchorOf(0))
    }

    @Test
    fun credential_field_label_matches_kind() {
        assertEquals("API Key", credentialFieldLabel(info()))
        assertEquals(
            "平台会话令牌（不是 sk- API Key）",
            credentialFieldLabel(info(balanceKind = QuotaCredentialKind.PLATFORM_TOKEN)),
        )
        assertEquals(
            "会话令牌",
            credentialFieldLabel(info(balanceKind = QuotaCredentialKind.SESSION_COOKIE)),
        )
    }

    private companion object {
        const val NOW = 1_700_000_000L
    }
}