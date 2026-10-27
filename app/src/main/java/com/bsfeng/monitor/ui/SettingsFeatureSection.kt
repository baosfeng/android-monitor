package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置「功能」分组 —— 实时监控 / 广告跳过 / 记账 / 传送 / 额度监控 / 页面轨迹 / 位置采集，一项功能一张卡（开关 + 该功能专属设置）。负责「开哪个功能、开了之后怎么配」。

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.SectionHeader
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.ToastHostState
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.setValue
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.MonitorState

/**
 * 设置「功能」分组 —— 实时监控 / 广告跳过 / 记账 / 传送 / 额度监控 / 页面轨迹 / 位置采集，一项功能一张卡（开关 + 该功能专属设置）。负责「开哪个功能、开了之后怎么配」。
 */
@Composable
internal fun SettingsFeatureSection(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    scope: CoroutineScope,
    state: MonitorState,
    viewModel: MainViewModel,
    liveOn: Boolean,
    adSkipOn: Boolean,
    financeOn: Boolean,
    transportOn: Boolean,
    locationOn: Boolean,
    quotaOn: Boolean,
    quotaSettings: QuotaSettings,
    quotaAlertsOnState: MutableState<Boolean>,
    quotaRefreshHoursState: MutableState<Int>,
    transferViewModel: TransferViewModel?,
    watchWindowMs: Long,
    ruleCount: Int,
    subscriptionCount: Int,
    whitelistCount: Int,
    locationPermissionState: MutableState<LocationPermissionStatus>,
    locationLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    backgroundLauncher: ManagedActivityResultLauncher<String, Boolean>,
    setFeature: (Feature, Boolean) -> Unit,
    openLocationAppSettings: () -> Unit,
    onNavigateToSkipRules: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToQuotaSettings: () -> Unit,
) {
    var quotaAlertsOn by quotaAlertsOnState
    var quotaRefreshHours by quotaRefreshHoursState

    // ===== 功能（置顶）：一项功能一张卡 = 开关 + 该功能专属设置 =====
    // 关闭后专属设置折叠成一句「开启后可用」：不消失、不换位置，重启后也找得到
    SectionHeader("功能")

    // 实时监控（Tab：实时）
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "实时监控",
            subtitle = if (liveOn) {
                "实时事件流与前台 App 记录"
            } else {
                "已关闭：不生成「实时」Tab"
            },
            icon = Icons.Outlined.Speed,
            trailing = { AppSwitch(liveOn, { setFeature(Feature.LIVE, it) }) },
        )
        TokenDivider(first = false)
        if (liveOn) {
            TokenRow(
                title = "今日记录",
                subtitle = "实时页按事件类型分组展示，概览实时入口照常可进",
                icon = Icons.Outlined.BarChart,
                trailing = { TailNum(state.todayEventCount.toString() + " 条") },
            )
        } else {
            TokenRow(
                title = "实时事件流",
                subtitle = FeatureCatalog.offHint(Feature.LIVE),
                icon = Icons.Outlined.BarChart,
            )
        }
        }

    SettingsSkipFeatureCard(
        t = t,
        view = view,
        toast = toast,
        viewModel = viewModel,
        adSkipOn = adSkipOn,
        watchWindowMs = watchWindowMs,
        ruleCount = ruleCount,
        subscriptionCount = subscriptionCount,
        whitelistCount = whitelistCount,
        setFeature = setFeature,
        onNavigateToSkipRules = onNavigateToSkipRules,
        onNavigateToWhitelist = onNavigateToWhitelist,
    )

    SettingsFinanceFeatureCard(
        t = t,
        context = context,
        view = view,
        toast = toast,
        financeOn = financeOn,
        setFeature = setFeature,
    )

    SettingsTransferFeatureCard(
        t = t,
        context = context,
        view = view,
        toast = toast,
        scope = scope,
        transportOn = transportOn,
        transferViewModel = transferViewModel,
        setFeature = setFeature,
    )

    SettingsQuotaFeatureCard(
        t = t,
        quotaOn = quotaOn,
        quotaSettings = quotaSettings,
        quotaRefreshHoursState = quotaRefreshHoursState,
        quotaAlertsOnState = quotaAlertsOnState,
        setFeature = setFeature,
        onNavigateToQuotaSettings = onNavigateToQuotaSettings,
    )

    SettingsLocationCard(
        t = t,
        context = context,
        view = view,
        toast = toast,
        locationOn = locationOn,
        locationPermissionState = locationPermissionState,
        locationLauncher = locationLauncher,
        backgroundLauncher = backgroundLauncher,
        setFeature = setFeature,
        openLocationAppSettings = openLocationAppSettings,
    )

    // 桌面小组件（隐藏金额 + 6 个添加到桌面）：不占 Tab、无功能开关，放在功能页最末
    SettingsWidgetSection(
        t = t,
        context = context,
        toast = toast,
        scope = scope,
    )
}
