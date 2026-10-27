package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind

/** Ollama 的稳定 id：多处按 id 分流（Key 管理页外链、计费模式条），只在这里写死一次。 */
internal const val OLLAMA_ID = "ollama"

/**
 * 额度页的 provider 展示模型。
 *
 * 为什么不直接把 [com.bsfeng.monitor.domain.quota.QuotaProvider] 交给 Compose：
 * provider 是纯数据拉取契约，不含「已配置 / 已连接」的界面语义，
 * 这里把它们摊平成 Compose 直接可消费的字段。
 */
data class QuotaProviderInfo(
    val id: String,
    val displayName: String,
    val connected: Boolean = false,
    val balanceConfigured: Boolean = false,
    val usageConfigured: Boolean = false,
    val supportsUsage: Boolean = true,
    val supportsModelBreakdown: Boolean = false,
    val balanceKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY,
    val usageKind: QuotaCredentialKind? = null,
    val credentialHint: String = "",
    val credentialHelp: String = "",
    val usageCredentialHint: String? = null,
    val usageCredentialHelp: String? = null,
)
