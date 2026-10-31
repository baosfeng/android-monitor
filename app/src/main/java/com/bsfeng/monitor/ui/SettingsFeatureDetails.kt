package com.bsfeng.monitor.ui

// 功能细则页的正文：实时 / 广告跳过 / 记账 / 传送 / 额度 / 位置 / 桌面小组件。
//
// 一级页「功能开关」只管开不开（见 ui/SettingsFeatureIndexSection.kt），
// 每项功能的专属设置全在这里；正文直接复用各功能卡，不复制一份 UI。
//
// 每页 ≤ 7 项（契约 s09 页层级规则 2）：超过的（传送）再拆一页，
// 而不是把 30 多行塞回同一屏。

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch

/** 实时监控细则：实时流说明 + 今日记录条数（功能开关在一级「功能开关」页）。 */
@Composable
internal fun SettingsLiveDetail(rt: SettingsScreenRuntime) {
    val t = rt.t
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "实时监控",
            subtitle = if (rt.liveOn) {
                "实时事件流与前台 App 记录"
            } else {
                "已关闭：不生成「实时」Tab"
            },
            icon = settingsPageIcon(SettingsSubPage.LIVE_DETAIL),
            trailing = {
                AppSwitch(rt.liveOn, { rt.setFeature(Feature.LIVE, it) })
            },
        )
        TokenDivider(first = false)
        if (rt.liveOn) {
            TokenRow(
                title = "今日记录",
                subtitle = "实时页按事件类型分组展示，概览实时入口照常可进",
                icon = settingsPageIcon(SettingsSubPage.LIVE_DETAIL),
                trailing = { TailNum(rt.state.todayEventCount.toString() + " 条") },
            )
        } else {
            TokenRow(
                title = "实时事件流",
                subtitle = FeatureCatalog.offHint(Feature.LIVE),
                icon = settingsPageIcon(SettingsSubPage.LIVE_DETAIL),
            )
        }
    }
}

/** 广告跳过细则：开关 + 监控时长窗口 + 规则 / 白名单入口，正文见 SettingsSkipFeatureCard.kt。 */
@Composable
internal fun SettingsSkipDetail(rt: SettingsScreenRuntime) {
    SettingsSkipFeatureCard(
        t = rt.t,
        view = rt.view,
        toast = rt.toast,
        viewModel = rt.viewModel,
        adSkipOn = rt.adSkipOn,
        watchWindowMs = rt.watchWindowMs,
        ruleCount = rt.ruleCount.value,
        subscriptionCount = rt.subscriptionCount.value,
        whitelistCount = rt.whitelistCount.value,
        setFeature = rt.setFeature,
        onNavigateToSkipRules = rt.onNavigateToSkipRules,
        onNavigateToWhitelist = rt.onNavigateToWhitelist,
    )
}

/** 记账细则：开关 + 支付后弹窗确认，正文见 SettingsFinanceFeatureCard.kt。 */
@Composable
internal fun SettingsFinanceDetail(rt: SettingsScreenRuntime) {
    SettingsFinanceFeatureCard(
        t = rt.t,
        context = rt.context,
        view = rt.view,
        toast = rt.toast,
        financeOn = rt.financeOn,
        setFeature = rt.setFeature,
    )
}

/**
 * 传送细则：开关 + 两扇门（接收 / 提醒与配对）。
 *
 * 传送的专属设置共 10 项，超过单页 7 项上限，所以本页只留开关与两个入口。
 */
@Composable
internal fun SettingsTransportDetail(rt: SettingsScreenRuntime, onOpenSubPage: (SettingsSubPage) -> Unit) {
    val t = rt.t
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "传送",
            subtitle = if (rt.transportOn) {
                "局域网互发文件 · 设置在此，传送页只留收发"
            } else {
                "已关闭：不启动传送服务、不占端口、不做设备发现"
            },
            icon = settingsPageIcon(SettingsSubPage.TRANSPORT_DETAIL),
            trailing = { AppSwitch(rt.transportOn, { rt.setFeature(Feature.TRANSPORT, it) }) },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "接收与接收目录",
            // 副标题逐项写清这一层管什么：用户不该为了找某个开关连开两层还不知道在哪
            subtitle = if (rt.transportOn) {
                "接收与目录 · 本机名称 / 后台接收 / 自动关闭 / 通知栏磁贴 / 目录清理"
            } else {
                FeatureCatalog.offHint(Feature.TRANSPORT) + "：接收与目录 · 本机名称 / 后台接收 / 目录清理"
            },
            icon = Icons.Outlined.Settings,
            onClick = { onOpenSubPage(SettingsSubPage.TRANSPORT_RECEIVING_DETAIL) },
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "提醒与免配对",
            subtitle = if (rt.transportOn) {
                "提示音 · 免配对 · 体积阈值 · 完成 / 失败 / 新设备三种提示"
            } else {
                FeatureCatalog.offHint(Feature.TRANSPORT) + "：提示音 · 免配对 · 体积阈值"
            },
            icon = Icons.Outlined.Settings,
            onClick = { onOpenSubPage(SettingsSubPage.TRANSPORT_ALERTS_DETAIL) },
            trailing = { TailChevron() },
        )
    }
}

/**
 * 传送 · 接收细则：本机名称 / 后台接收 / 自动关闭接收 / 通知栏磁贴 / 接收目录，
 * 正文见 SettingsTransferReceiving.kt。
 */
@Composable
internal fun SettingsTransportReceivingDetail(rt: SettingsScreenRuntime) {
    val t = rt.t
    val transferVm = rt.transferViewModel
    if (rt.transportOn && transferVm != null) {
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            SettingsTransferReceivingRows(
                t = t,
                transferViewModel = transferVm,
                toast = rt.toast,
                context = rt.context,
                scope = rt.scope,
                view = rt.view,
            )
        }
    } else {
        // 关闭态不建 ViewModel：不启动服务、不占端口、不做设备发现，这里只给一句回来路
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            TokenRow(
                title = "传送设置",
                subtitle = FeatureCatalog.offHint(Feature.TRANSPORT),
                icon = Icons.Outlined.Settings,
            )
        }
    }
}

/** 传送 · 提醒与配对细则：提示音 / 免配对窗口 / 体积阈值，正文见 SettingsTransferPairingRows.kt。 */
@Composable
internal fun SettingsTransportAlertsDetail(rt: SettingsScreenRuntime) {
    val t = rt.t
    val transferVm = rt.transferViewModel
    if (rt.transportOn && transferVm != null) {
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            SettingsTransferPairingRows(
                t = t,
                view = rt.view,
                toast = rt.toast,
                transferViewModel = transferVm,
            )
        }
    } else {
        AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
            TokenRow(
                title = "提醒与免配对",
                subtitle = FeatureCatalog.offHint(Feature.TRANSPORT),
                icon = Icons.Outlined.Settings,
            )
        }
    }
}

/** 额度细则：开关 + Provider 与凭据 / 预警通知 / 后台刷新，正文见 SettingsQuotaFeatureCard.kt。 */
@Composable
internal fun SettingsQuotaDetail(rt: SettingsScreenRuntime) {
    SettingsQuotaFeatureCard(
        t = rt.t,
        quotaOn = rt.quotaOn,
        quotaSettings = rt.quotaSettings,
        quotaRefreshHoursState = rt.quotaRefreshHoursState,
        quotaAlertsOnState = rt.quotaAlertsOnState,
        setFeature = rt.setFeature,
        onNavigateToQuotaSettings = rt.onNavigateToQuotaSettings,
    )
}

/**
 * 位置细则：开关 + 四态权限引导 + 采集覆盖。
 *
 * 两张卡分工不同，顺序不能反：引导卡回答「权限对不对」（现在能不能采），
 * 覆盖卡回答「过去 24 小时到底采到了多少」（一直在采吗）。先看前者再看后者，
 * 用户才能区分「没开权限」和「开了但掉线」—— 这正是这轮要解决的两种不同故障。
 *
 * 覆盖卡只在功能开启时渲染：功能关着时它只会是一整条空态，反而误导用户以为在采集。
 */
@Composable
internal fun SettingsLocationDetail(rt: SettingsScreenRuntime) {
    SettingsLocationCard(
        t = rt.t,
        context = rt.context,
        view = rt.view,
        toast = rt.toast,
        locationOn = rt.locationOn,
        locationPermissionState = rt.locationPermission,
        locationLauncher = rt.locationLauncher,
        backgroundLauncher = rt.backgroundLauncher,
        setFeature = rt.setFeature,
        openLocationAppSettings = rt.openLocationAppSettings,
    )
    if (rt.locationOn) {
        SettingsLocationCoverageCard(modifier = Modifier.padding(top = rt.t.space2))
    }
}

/** 桌面小组件细则：隐藏金额 + 六个「添加到桌面」，正文见 SettingsWidgetSection.kt。 */
@Composable
internal fun SettingsWidgetDetail(rt: SettingsScreenRuntime) {
    SettingsWidgetSection(
        t = rt.t,
        context = rt.context,
        toast = rt.toast,
        scope = rt.scope,
    )
}