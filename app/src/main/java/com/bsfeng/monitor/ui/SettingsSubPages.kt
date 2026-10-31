package com.bsfeng.monitor.ui

// 二级页正文派发：页面枚举（ui/SettingsSubPage.kt）→ 各组正文。
//
// 一级页 6 组走 Settings{Monitor,FeatureIndex,Backup,Appearance,About,Debug}Section；
// 功能细则 7 页复用各自的功能卡（实时 / 广告跳过 / 记账 / 传送 / 额度 / 位置 / 桌面小组件）。

import androidx.compose.runtime.Composable

/**
 * 一个二级页的正文。
 *
 * 每页 ≤ 7 项（契约 s09 页层级）：「功能开关」页只有 6 行开关 + chevron，
 * 各功能自己的设置一律下钻到 *_DETAIL 页，不在同一屏堆 37 行。
 */
@Composable
internal fun SettingsSubPageBody(
    page: SettingsSubPage,
    rt: SettingsScreenRuntime,
    onOpenSubPage: (SettingsSubPage) -> Unit,
) {
    when (page) {
        SettingsSubPage.GENERAL -> SettingsMonitorSection(rt)
        SettingsSubPage.FEATURE -> SettingsFeatureIndexSection(rt, onOpenSubPage)
        SettingsSubPage.DATA -> SettingsBackupSection(rt)
        SettingsSubPage.APPEARANCE -> SettingsAppearanceSection(rt)
        SettingsSubPage.ABOUT -> SettingsAboutSection(rt)
        SettingsSubPage.DEBUG -> SettingsDebugSection(rt)

        SettingsSubPage.LIVE_DETAIL -> SettingsLiveDetail(rt)
        SettingsSubPage.SKIP_DETAIL -> SettingsSkipDetail(rt)
        SettingsSubPage.FINANCE_DETAIL -> SettingsFinanceDetail(rt)
        SettingsSubPage.TRANSPORT_DETAIL -> SettingsTransportDetail(rt, onOpenSubPage)
        SettingsSubPage.TRANSPORT_RECEIVING_DETAIL -> SettingsTransportReceivingDetail(rt)
        SettingsSubPage.TRANSPORT_ALERTS_DETAIL -> SettingsTransportAlertsDetail(rt)
        SettingsSubPage.QUOTA_DETAIL -> SettingsQuotaDetail(rt)
        SettingsSubPage.LOCATION_DETAIL -> SettingsLocationDetail(rt)
        SettingsSubPage.WIDGET_DETAIL -> SettingsWidgetDetail(rt)
    }
}
