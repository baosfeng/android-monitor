package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的分组正文 —— 五态容器（空 / 错 / 边界 / 有数据）包住五个分组
// （功能 / 通用 / 动效与触感 / 数据 / 关于）。负责「设置页中间那一整块可滚动内容」。

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.StateContainer
import kotlinx.coroutines.flow.MutableStateFlow
import com.bsfeng.monitor.update.UpdateUiState
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import kotlinx.coroutines.CoroutineScope
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.MonitorState
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.components.FiveState

/**
 * 设置页的分组正文：五态容器 + 五个分组。
 *
 * 分组顺序与间距沿用改动前：外层 verticalScroll 的 space4 + 单列 Column 的 space4。
 */
@Composable
internal fun SettingsSections(rt: SettingsScreenRuntime) {
    val t = rt.t
    val context = rt.context
    val view = rt.view
    val toast = rt.toast
    val scope = rt.scope
    val state = rt.state
    val demoState = rt.demoStateState.value
    val liveOn = rt.liveOn
    val adSkipOn = rt.adSkipOn
    val financeOn = rt.financeOn
    val transportOn = rt.transportOn
    val pageTraceOn = rt.pageTraceOn
    val locationOn = rt.locationOn
    val quotaOn = rt.quotaOn
    val quotaSettings = rt.quotaSettings
    val quotaAlertsOnState = rt.quotaAlertsOnState
    val quotaRefreshHoursState = rt.quotaRefreshHoursState
    val transferViewModel = rt.transferViewModel
    val watchWindowMs = rt.watchWindowMs
    val ruleCount = rt.ruleCount.value
    val subscriptionCount = rt.subscriptionCount.value
    val whitelistCount = rt.whitelistCount.value
    val locationPermission = rt.locationPermission.value
    val locationLauncher = rt.locationLauncher
    val backgroundLauncher = rt.backgroundLauncher
    val isPaused = rt.isPaused
    val themeId = rt.themeId
    val batteryIgnored = rt.batteryIgnored.value
    val notificationsOn = rt.notificationsOn.value
    val heartbeatNoticeState = rt.heartbeatNoticeState
    val localAppearanceState = rt.localAppearanceState
    val motionConfig = rt.motionConfig
    val motionTier = rt.motionTier
    val autoDowngraded = rt.autoDowngraded
    val hapticEnabled = rt.hapticEnabled
    val hapticStrength = rt.hapticStrength
    val backupFrequencyState = rt.backupFrequencyState
    val lastBackup = rt.lastBackup.value
    val backupCount = rt.backupCount.value
    val backupBusyState = rt.backupBusyState
    val restoreBusyState = rt.restoreBusyState
    val pendingRestoreUriState = rt.pendingRestoreUriState
    val restoreLauncher = rt.restoreLauncher
    val debugUi = rt.debugUi
    val demoVisible = rt.demoVisible
    val debugEnabled = rt.debugEnabled
    val localGalleryState = rt.localGalleryState
    val updateState = rt.updateState
    val setFeature = rt.setFeature
    val reloadCounters = rt.reloadCounters
    val openLocationAppSettings = rt.openLocationAppSettings
    val viewModel = rt.viewModel
    val effective = rt.effective
    val onNavigateToSkipRules = rt.onNavigateToSkipRules
    val onNavigateToWhitelist = rt.onNavigateToWhitelist
    val onNavigateToAppearance = rt.onNavigateToAppearance
    val onNavigateToGallery = rt.onNavigateToGallery
    val onNavigateToQuotaSettings = rt.onNavigateToQuotaSettings
    // 五态统一走 StateContainer（ui/components/States.kt）：
    // 空/错态 = 居中列 + 44dp 图标 + 17sp 标题 + 14sp 描述 + CTA，文案取自原型 s09-st-*。
    StateContainer(
        state = effective,
        modifier = Modifier.fillMaxWidth(),
        emptyTitle = "暂无可用设置项",
        emptyText = "无障碍服务尚未授权，开启后才能调整监控与开屏跳过选项。",
        emptyActionLabel = "前往系统设置",
        onEmptyAction = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        errorTitle = "读取配置失败",
        errorText = "本地配置存储被拒绝（EACCES），上次读取 9:40 失败。",
        onRetry = {
            reloadCounters()
            toast.show("正在重试读取配置…")
        },
        edgeText = "边界态：超长应用名 / 10,000+ 条计数",
        edgeContent = {
            TokenRow(
                title = "国家电网掌上电力营业厅客户端（企业内网版）",
                trailing = { TailNum("10,286 条") },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "中国建设银行个人网上银行移动客户端",
                trailing = { TailNum("10,000 条") },
            )
            TokenDivider(first = false)
            TokenRow(
                title = "超长标题：该应用在后台持续拉起前台服务并申请了忽略电池优化的白名单权限",
                trailing = { TailNum("9,999 条") },
            )
        },
    ) {
        // StateContainer 的 POPULATED 分支把 content 放进 Box（States.kt:90）：
        // Box 下多个兄弟节点会叠在同一原点（「只剩后两组 + 不可滚动」的根因）。
        // 分组先收进一个 Column，Box 下只留这一个子节点；组间距沿用外层原先的 space4。
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(t.space4),
        ) {
            SettingsFeatureSection(
                t = t,
                context = context,
                view = view,
                toast = toast,
                scope = scope,
                state = state,
                viewModel = viewModel,
                liveOn = liveOn,
                adSkipOn = adSkipOn,
                financeOn = financeOn,
                transportOn = transportOn,
                pageTraceOn = pageTraceOn,
                locationOn = locationOn,
                quotaOn = quotaOn,
                quotaSettings = quotaSettings,
                quotaAlertsOnState = quotaAlertsOnState,
                quotaRefreshHoursState = quotaRefreshHoursState,
                transferViewModel = transferViewModel,
                watchWindowMs = watchWindowMs,
                ruleCount = ruleCount,
                subscriptionCount = subscriptionCount,
                whitelistCount = whitelistCount,
                locationPermission = locationPermission,
                locationLauncher = locationLauncher,
                backgroundLauncher = backgroundLauncher,
                setFeature = setFeature,
                openLocationAppSettings = openLocationAppSettings,
                onNavigateToSkipRules = onNavigateToSkipRules,
                onNavigateToWhitelist = onNavigateToWhitelist,
                onNavigateToQuotaSettings = onNavigateToQuotaSettings,
            )

            SettingsGeneralSection(
                t = t,
                context = context,
                view = view,
                toast = toast,
                state = state,
                viewModel = viewModel,
                isPaused = isPaused,
                themeId = themeId,
                batteryIgnored = batteryIgnored,
                notificationsOn = notificationsOn,
                locationOn = locationOn,
                locationPermission = locationPermission,
                heartbeatNoticeState = heartbeatNoticeState,
                localAppearanceState = localAppearanceState,
                onNavigateToAppearance = onNavigateToAppearance,
            )

            SettingsMotionHapticSection(
                t = t,
                view = view,
                motionConfig = motionConfig,
                motionTier = motionTier,
                autoDowngraded = autoDowngraded,
                hapticEnabled = hapticEnabled,
                hapticStrength = hapticStrength,
            )

            SettingsDataSection(
                t = t,
                context = context,
                view = view,
                toast = toast,
                scope = scope,
                backupFrequencyState = backupFrequencyState,
                lastBackup = lastBackup,
                backupCount = backupCount,
                backupBusyState = backupBusyState,
                restoreBusyState = restoreBusyState,
                pendingRestoreUriState = pendingRestoreUriState,
                restoreLauncher = restoreLauncher,
                reloadCounters = reloadCounters,
            )

            SettingsAboutSection(
                t = t,
                view = view,
                toast = toast,
                scope = scope,
                viewModel = viewModel,
                updateState = updateState,
                debugUi = debugUi,
                demoVisible = demoVisible,
                debugEnabled = debugEnabled,
                localGalleryState = localGalleryState,
                onNavigateToGallery = onNavigateToGallery,
            )

            Spacer(Modifier.height(t.space6))
        }
    }
}
