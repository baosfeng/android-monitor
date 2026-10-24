package com.bsfeng.monitor.ui.quota

// 从 ui/quota/QuotaSettingsProviders.kt 拆出：provider 的状态与类型文案 —— statusText（连接状态）、kindLabel（凭据类型）。负责「provider 状态怎么显示成人话」。

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind

internal fun statusText(info: QuotaProviderInfo): String {
    val kinds = buildList {
        if (info.balanceConfigured) add(kindLabel(info.balanceKind))
        if (info.usageConfigured && info.usageKind != null && info.usageKind != info.balanceKind) {
            add(kindLabel(info.usageKind))
        }
    }
    return when {
        kinds.isNotEmpty() -> "已连接 · 已配置 ${kinds.joinToString(" / ")}"
        info.connected -> "已连接"
        info.usageKind != null -> "未配置 · 余额 + 用量（需平台登录令牌）"
        else -> "未配置"
    }
}

internal fun kindLabel(kind: QuotaCredentialKind): String = when (kind) {
    QuotaCredentialKind.API_KEY -> "API Key"
    QuotaCredentialKind.PLATFORM_TOKEN -> "平台登录令牌"
    QuotaCredentialKind.SESSION_COOKIE -> "网页 Cookie"
}
