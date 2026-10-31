package com.bsfeng.monitor.ui

// 功能细则页「额度监控」的卡 —— 额度开关、provider 与凭据入口、预警通知、后台刷新。负责「额度页开不开、怎么配」。

import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 设置「功能 · 额度监控」卡 —— 额度开关、provider 与凭据入口、预警通知、后台刷新。负责「额度页开不开、怎么配」。
 */
@Composable
internal fun SettingsQuotaFeatureCard(
    t: DesignTokens,
    quotaOn: Boolean,
    quotaSettings: QuotaSettings,
    quotaRefreshHoursState: MutableState<Int>,
    quotaAlertsOnState: MutableState<Boolean>,
    setFeature: (Feature, Boolean) -> Unit,
    onNavigateToQuotaSettings: () -> Unit,
) {
    var quotaAlertsOn by quotaAlertsOnState
    var quotaRefreshHours by quotaRefreshHoursState

// 每行各持一份 interaction：共用会让多行同时进入按压态
val haptic = rememberHaptic()
val providerInteraction = remember { MutableInteractionSource() }
val refreshInteraction = remember { MutableInteractionSource() }

// 额度监控（Tab：额度；默认开启）：provider 凭据、预警、后台刷新
AppCard(Modifier.padding(horizontal = t.space4).testTag("s09-quota"), padding = 0.dp) {
// 副标题锚点落在这里（s09-quota-sub）：TokenRow 拿不到 subtitle 节点，故用 SettingsRow
SettingsRow(
    title = "额度监控",
    subtitle = if (quotaOn) {
        "聚合 AI 服务的余额与用量 · " + QuotaSettings.labelOf(quotaRefreshHours) + "刷新"
    } else {
        "已关闭：不生成「额度」Tab，也不后台查询"
    },
    subtitleModifier = Modifier.testTag("s09-quota-sub"),
    icon = Icons.Outlined.AccountBalanceWallet,
    trailing = {
        AppSwitch(
            checked = quotaOn,
            onCheckedChange = { setFeature(Feature.QUOTA, it) },
            // 开关锚点（s09-quota-switch）：关闭后焦点留在开关本身
            modifier = Modifier.testTag("s09-quota-switch"),
            contentDescription = "额度",
        )
    },
)
if (quotaOn) {
    // 子项区（s09-quota-body）：关闭时整块被「额度设置」一行替换，不留空档
    Column(Modifier.fillMaxWidth().testTag("s09-quota-body")) {
        TokenDivider(first = false)
        TokenRow(
            title = "Provider 与凭据",
            modifier = Modifier
                .testTag("s09-goto-quota")
                .pressFeedback(providerInteraction)
                .clickable(
                    interactionSource = providerInteraction,
                    indication = LocalIndication.current,
                ) {
                    haptic(HapticTone.LIGHT)
                    onNavigateToQuotaSettings()
                },
            subtitle = "Ollama API Key · DeepSeek Key 与平台令牌",
            icon = Icons.Outlined.Key,
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "预警通知",
            subtitle = "剩余 5% / 4% / 3% / 2% / 1% 各提醒一次",
            icon = Icons.Outlined.Notifications,
            trailing = {
                AppSwitch(
                    quotaAlertsOn,
                    {
                        quotaAlertsOn = it
                        quotaSettings.alertsEnabled = it
                    },
                    contentDescription = "预警通知",
                )
            },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "后台刷新",
            modifier = Modifier
                .testTag("s09-quota-refresh")
                .pressFeedback(refreshInteraction)
                .clickable(
                    interactionSource = refreshInteraction,
                    indication = LocalIndication.current,
                ) {
                    haptic(HapticTone.LIGHT)
                    onNavigateToQuotaSettings()
                },
            subtitle = "息屏不请求 · 解锁后不补跑",
            icon = Icons.Outlined.Refresh,
            trailing = { TailNum(QuotaSettings.labelOf(quotaRefreshHours)) },
        )
    }
} else {
    TokenDivider(first = false)
    TokenRow(
        title = "额度设置",
        subtitle = FeatureCatalog.offHint(Feature.QUOTA) +
            "：provider、预警、后台刷新",
        icon = Icons.Outlined.Settings,
    )
}
Spacer(Modifier.height(t.space1))
}
}
