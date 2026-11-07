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
        // 凭据类型必须按 provider 实际取值：Ollama / opencode-go 是 API Key、DeepSeek 是平台登录令牌、
        // OpenDesign 是网页 Cookie。写死「平台登录令牌」会让别家显示成错的填写指引（真机 2026-11-02 暴露）。
        info.usageKind != null -> "未配置 · 余额 + 用量（需" + credentialNeedSuffix(info.balanceKind) + "）"
        else -> "未配置"
    }
}

internal fun kindLabel(kind: QuotaCredentialKind): String = when (kind) {
    QuotaCredentialKind.API_KEY -> "API Key"
    QuotaCredentialKind.PLATFORM_TOKEN -> "平台登录令牌"
    QuotaCredentialKind.SESSION_COOKIE -> "网页 Cookie"
}

/**
 * 「需」后面接凭据名的写法：中文名前不加空格（需平台登录令牌），
 * 英文/字母开头的名字前加半角空格（需 API Key）—— 中西文混排的排版惯例，不加会挤在一起。
 */
internal fun credentialNeedSuffix(kind: QuotaCredentialKind): String {
    val label = kindLabel(kind)
    val startsWithLatin = label.firstOrNull()?.isLetter() == true && label.first() in 'A'..'z'
    return if (startsWithLatin) " " + label else label
}

