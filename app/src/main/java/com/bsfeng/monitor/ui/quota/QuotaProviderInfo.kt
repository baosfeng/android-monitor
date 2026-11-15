package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind

/** Ollama 的稳定 id：多处按 id 分流（Key 管理页外链、计费模式条），只在这里写死一次。 */
internal const val OLLAMA_ID = "ollama"

/**
 * 额度页的一个**账号实例**展示模型（同一 provider 类型可挂多行）。
 *
 * 为什么不直接把 [com.bsfeng.monitor.domain.quota.QuotaProvider] 交给 Compose：
 * provider 是纯数据拉取口径，不含「已配置 / 已连接 / 叫什么名字」的界面语义，
 * 这里把它们摊平成 Compose 直接可消费的字段。
 *
 * [instanceId] 是凭据 / 状态 / 快照 / 通知的键；[providerTypeId] 只决定卡片排版与锚点分流。
 */
data class QuotaProviderInfo(
    val instanceId: String,
    val providerTypeId: String,
    /** 行标题：改过名用名字，未改名时第一实例用类型名、其余用「账号 N」。 */
    val displayName: String,
    /** 类型名（设置页分组标题、单家卡片副标题）。 */
    val typeDisplayName: String = displayName,
    /** 用户改过的名字；空 = 未改名。 */
    val label: String = "",
    /** 该类型是否挂了多个账号（chip / 榜单要不要带账号名）。 */
    val multiAccount: Boolean = false,
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
) {
    /** 是否已是该类型唯一的账号（删账号入口据此禁用）。 */
    val onlyAccount: Boolean get() = !multiAccount
}
