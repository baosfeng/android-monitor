package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * provider 行副行文案的契约。
 *
 * 这条在真机上暴露过一次（2026-11-02）：Ollama 清空凭据后显示「需平台登录令牌」，
 * 但 Ollama 用的是 API Key —— 文案按 provider 写死会让用户去填一种不存在的凭据。
 */
class QuotaProviderTextTest {

    private fun info(
        id: String,
        balanceKind: QuotaCredentialKind,
        usageKind: QuotaCredentialKind? = null,
        connected: Boolean = false,
        balanceConfigured: Boolean = false,
        usageConfigured: Boolean = false,
    ) = QuotaProviderInfo(
        id = id,
        displayName = id,
        connected = connected,
        balanceConfigured = balanceConfigured,
        usageConfigured = usageConfigured,
        balanceKind = balanceKind,
        usageKind = usageKind,
    )

    @Test
    fun `已配置时列出凭据类型`() {
        val text = statusText(
            info(
                "ollama", QuotaCredentialKind.API_KEY,
                balanceConfigured = true, connected = true,
            )
        )
        assertEquals("已连接 · 已配置 API Key", text)
    }

    @Test
    fun `未配置时凭据类型按 provider 取值而不是写死平台登录令牌`() {
        // Ollama：API Key 型，清空后必须提示 API Key
        assertEquals(
            "未配置 · 余额 + 用量（需 API Key）",
            statusText(info("ollama", QuotaCredentialKind.API_KEY, QuotaCredentialKind.API_KEY)),
        )
        // DeepSeek：平台登录令牌型
        assertEquals(
            "未配置 · 余额 + 用量（需平台登录令牌）",
            statusText(
                info(
                    "deepseek", QuotaCredentialKind.PLATFORM_TOKEN, QuotaCredentialKind.PLATFORM_TOKEN,
                )
            ),
        )
        // OpenDesign：网页 Cookie 型
        assertEquals(
            "未配置 · 余额 + 用量（需网页 Cookie）",
            statusText(
                info(
                    "opendesign", QuotaCredentialKind.SESSION_COOKIE, QuotaCredentialKind.SESSION_COOKIE,
                )
            ),
        )
    }

    @Test
    fun `无用量接口的 provider 只显示未配置`() {
        assertEquals(
            "未配置",
            statusText(info("bare", QuotaCredentialKind.API_KEY, usageKind = null)),
        )
    }

    @Test
    fun `两种凭据类型不同时并列显示`() {
        val text = statusText(
            info(
                "mixed", QuotaCredentialKind.API_KEY,
                usageKind = QuotaCredentialKind.PLATFORM_TOKEN,
                connected = true, balanceConfigured = true, usageConfigured = true,
            )
        )
        assertEquals("已连接 · 已配置 API Key / 平台登录令牌", text)
    }
}
