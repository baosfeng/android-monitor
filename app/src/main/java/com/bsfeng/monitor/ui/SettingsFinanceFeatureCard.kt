package com.bsfeng.monitor.ui

// 功能细则页「记账」的卡 —— 记账开关与「支付后弹窗确认」。负责「记账开不开、自动记账弹不弹窗」。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.autobook.AutoBookConfig
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 设置「功能 · 记账」卡 —— 记账开关与「支付后弹窗确认」。负责「记账开不开、自动记账弹不弹窗」。
 */
@Composable
internal fun SettingsFinanceFeatureCard(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    financeOn: Boolean,
    setFeature: (Feature, Boolean) -> Unit,
) {
// 记账（Tab：记账）
AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
TokenRow(
    title = "记账",
    subtitle = if (financeOn) {
        "手动记账、统计与自动记账"
    } else {
        "已关闭：不生成「记账」Tab"
    },
    icon = Icons.Outlined.Payments,
    trailing = { AppSwitch(financeOn, { setFeature(Feature.FINANCE, it) }) },
)
TokenDivider(first = false)
if (financeOn) {
    val autoBookEnabled by AutoBookConfig.enabledFlow.collectAsState()
    TokenRow(
        title = "支付后弹窗确认",
        subtitle = if (autoBookEnabled) {
            "识别支付页，确认后入账"
        } else {
            "已关闭：不识别、不弹窗"
        },
        icon = Icons.Outlined.Payments,
        trailing = {
            AppSwitch(autoBookEnabled, { enabled ->
                AutoBookConfig.setEnabled(context, enabled)
                view.performConfirmHaptic()
                toast.show(
                    if (enabled) "已开启支付后弹窗记账" else "已关闭支付后弹窗记账"
                )
            })
        },
    )
} else {
    TokenRow(
        title = "自动记账",
        subtitle = FeatureCatalog.offHint(Feature.FINANCE),
        icon = Icons.Outlined.Payments,
    )
}
Spacer(Modifier.height(t.space1))
}
}
