package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页二级页正文 —— 一级页只留四组入口，各组原有分组正文
// （功能 / 通用 + 动效与触感 / 数据与备份 / 关于与调试）在二级页里原样复用、不改内部。
// 五态容器（加载 / 空 / 错 / 边界）在一级与二级页共用同一份文案与重试动作。

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.foundation.pageBackdrop

/**
 * 一级页「设置」的四个入口分组，点进全屏二级页；三级页（跳过规则 / 白名单 / 额度设置 /
 * 外观 / 组件库）仍走各自既有页面的跳转。
 *
 * 用「名字」作为可保存状态（见 MainContent 的 rememberSaveable 字符串），
 * 旋转或进程重建后能回到同一个二级页；[meta] 是二级页页头的一句话说明。
 */
enum class SettingsSubPage(val title: String, val meta: String) {
    FEATURE("功能", "开启后才会生成对应 Tab 与服务"),
    GENERAL("通用", "服务与系统权限 · 监控开关 · 外观与动效"),
    DATA("数据与备份", "本机打包、还原与自动备份"),
    ABOUT("关于与调试", "版本 · 更新 · 开源许可与调试入口"),
}

/**
 * 设置页五态容器（一级与二级共用）。
 *
 * 空/错态 = 居中列 + 44dp 图标 + 17sp 标题 + 14sp 描述 + CTA，文案取自原型 s09-st-*；
 * 边界态沿用既有超长标题 / 五位计数样例。
 */
@Composable
internal fun SettingsStateContainer(
    rt: SettingsScreenRuntime,
    content: @Composable () -> Unit,
) {
    StateContainer(
        state = rt.effective,
        modifier = Modifier.fillMaxWidth(),
        emptyTitle = "暂无可用设置项",
        emptyText = "无障碍服务尚未授权，开启后才能调整监控与开屏跳过选项。",
        emptyActionLabel = "前往系统设置",
        onEmptyAction = {
            rt.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        errorTitle = "读取配置失败",
        errorText = "本地配置存储被拒绝（EACCES），上次读取 9:40 失败。",
        onRetry = {
            rt.reloadCounters()
            rt.toast.show("正在重试读取配置…")
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
        content = content,
    )
}

/**
 * 二级页整屏：页头（带返回）+ 五态容器 + 该组正文。
 *
 * 二级页不常驻，返回即卸载；一级页滚动位置由调用方保留（一级页始终在组合树里，
 * 二级页以覆盖层形式叠加，见 ui/SettingsScreen.kt）。
 */
@Composable
internal fun SettingsSubPageScreen(
    page: SettingsSubPage,
    rt: SettingsScreenRuntime,
    onBack: () -> Unit,
) {
    val t = rt.t
    Column(
        Modifier
            .fillMaxSize()
            .pageBackdrop()
    ) {
        ScreenHead(title = page.title, meta = page.meta, onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(t.space4),
        ) {
            SettingsStateContainer(rt) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(t.space4),
                ) {
                    when (page) {
                        SettingsSubPage.FEATURE -> SettingsFeatureGroup(rt)
                        SettingsSubPage.GENERAL -> {
                            SettingsGeneralGroup(rt)
                            SettingsMotionHapticGroup(rt)
                        }
                        SettingsSubPage.DATA -> SettingsDataGroup(rt)
                        SettingsSubPage.ABOUT -> SettingsAboutGroup(rt)
                    }
                    Spacer(Modifier.height(t.space6))
                }
            }
        }
    }
}

/** 二级页「功能」：一项功能一张卡（开关 + 该功能专属设置），正文见 SettingsFeatureSection.kt。 */
@Composable
private fun SettingsFeatureGroup(rt: SettingsScreenRuntime) {
    SettingsFeatureSection(
        t = rt.t,
        context = rt.context,
        view = rt.view,
        toast = rt.toast,
        scope = rt.scope,
        state = rt.state,
        viewModel = rt.viewModel,
        liveOn = rt.liveOn,
        adSkipOn = rt.adSkipOn,
        financeOn = rt.financeOn,
        transportOn = rt.transportOn,
        locationOn = rt.locationOn,
        quotaOn = rt.quotaOn,
        quotaSettings = rt.quotaSettings,
        quotaAlertsOnState = rt.quotaAlertsOnState,
        quotaRefreshHoursState = rt.quotaRefreshHoursState,
        transferViewModel = rt.transferViewModel,
        watchWindowMs = rt.watchWindowMs,
        ruleCount = rt.ruleCount.value,
        subscriptionCount = rt.subscriptionCount.value,
        whitelistCount = rt.whitelistCount.value,
        locationPermission = rt.locationPermission.value,
        locationLauncher = rt.locationLauncher,
        backgroundLauncher = rt.backgroundLauncher,
        setFeature = rt.setFeature,
        openLocationAppSettings = rt.openLocationAppSettings,
        onNavigateToSkipRules = rt.onNavigateToSkipRules,
        onNavigateToWhitelist = rt.onNavigateToWhitelist,
        onNavigateToQuotaSettings = rt.onNavigateToQuotaSettings,
    )
}

/** 二级页「通用」上半：无障碍服务 / 监控开关 / 电池优化 / 外观 / 通知，正文见 SettingsGeneralSection.kt。 */
@Composable
private fun SettingsGeneralGroup(rt: SettingsScreenRuntime) {
    SettingsGeneralSection(
        t = rt.t,
        context = rt.context,
        view = rt.view,
        toast = rt.toast,
        state = rt.state,
        viewModel = rt.viewModel,
        isPaused = rt.isPaused,
        themeId = rt.themeId,
        batteryIgnored = rt.batteryIgnored.value,
        notificationsOn = rt.notificationsOn.value,
        locationOn = rt.locationOn,
        locationPermission = rt.locationPermission.value,
        heartbeatNoticeState = rt.heartbeatNoticeState,
        localAppearanceState = rt.localAppearanceState,
        onNavigateToAppearance = rt.onNavigateToAppearance,
    )
}

/** 二级页「通用」下半：动效档位 / 自动降档 / 触觉开关与强度，正文见 SettingsMotionHapticSection.kt。 */
@Composable
private fun SettingsMotionHapticGroup(rt: SettingsScreenRuntime) {
    SettingsMotionHapticSection(
        t = rt.t,
        view = rt.view,
        motionConfig = rt.motionConfig,
        motionTier = rt.motionTier,
        autoDowngraded = rt.autoDowngraded,
        hapticEnabled = rt.hapticEnabled,
        hapticStrength = rt.hapticStrength,
    )
}

/** 二级页「数据与备份」：立即备份 / 还原 / 自动频率 / 最近一次，正文见 SettingsDataSection.kt。 */
@Composable
private fun SettingsDataGroup(rt: SettingsScreenRuntime) {
    SettingsDataSection(
        t = rt.t,
        context = rt.context,
        view = rt.view,
        toast = rt.toast,
        scope = rt.scope,
        backupFrequencyState = rt.backupFrequencyState,
        lastBackup = rt.lastBackup.value,
        backupCount = rt.backupCount.value,
        backupBusyState = rt.backupBusyState,
        restoreBusyState = rt.restoreBusyState,
        pendingRestoreUriState = rt.pendingRestoreUriState,
        restoreLauncher = rt.restoreLauncher,
        reloadCounters = rt.reloadCounters,
    )
}

/** 二级页「关于与调试」：版本（长按解锁调试）/ 检查更新 / 许可 / 组件库，正文见 SettingsAboutSection.kt。 */
@Composable
private fun SettingsAboutGroup(rt: SettingsScreenRuntime) {
    SettingsAboutSection(
        t = rt.t,
        view = rt.view,
        toast = rt.toast,
        scope = rt.scope,
        viewModel = rt.viewModel,
        updateState = rt.updateState,
        debugUi = rt.debugUi,
        demoVisible = rt.demoVisible,
        debugEnabled = rt.debugEnabled,
        localGalleryState = rt.localGalleryState,
        onNavigateToGallery = rt.onNavigateToGallery,
    )
}
