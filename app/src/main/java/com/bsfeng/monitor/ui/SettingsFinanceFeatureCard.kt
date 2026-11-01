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
    /** 下钻「桌面小组件」页：组件展示的是记账数据，入口放这里语义最近。 */
    onOpenWidgetDetail: () -> Unit = {},
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
    trailing = {
        AppSwitch(financeOn, { setFeature(Feature.FINANCE, it) }, contentDescription = "记账")
    },
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
            }, contentDescription = "支付后弹窗确认")
        },
    )
} else {
    TokenRow(
        title = "自动记账",
        subtitle = FeatureCatalog.offHint(Feature.FINANCE),
        icon = Icons.Outlined.Payments,
    )
}
// 桌面小组件属于记账呈现（隐藏金额 / 六个组件），从功能开关页挪到记账细则，
// 功能开关页因此回到 7 项（锚点 s09 每页 ≤ 7 项）。
TokenDivider(first = false)
TokenRow(
    title = "桌面小组件",
    subtitle = "显示本月支出 · 隐藏金额开关 · 添加到桌面",
    icon = settingsPageIcon(SettingsSubPage.WIDGET_DETAIL),
    onClick = onOpenWidgetDetail,
    trailing = { TailChevron() },
)
Spacer(Modifier.height(t.space1))
}
}
