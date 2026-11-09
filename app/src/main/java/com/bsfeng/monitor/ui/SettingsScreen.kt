package com.bsfeng.monitor.ui

// 设置（原型 s09-settings.html）：一级页 = 快捷区（无障碍服务 / 前台应用监控）+ 六组入口，
// 点进全屏二级页；二级页正文见 ui/SettingsSubPages.kt，页面清单见 ui/SettingsSubPage.kt。
// 取数与状态在 ui/SettingsScreenRuntime.kt；本屏只做一级页渲染与覆盖层接线。
//
// 一级页始终留在组合树里、二级页以覆盖层叠加：返回时一级页的滚动位置天然保留。
// 二级页状态由 MainContent 持有（rememberSaveable + 枚举名），旋转 / 进程重建可恢复。
//
// 信息架构口径（为什么这么分）：一级页只回答「去哪儿」，六组按「运行前提 / 功能 / 数据 /
// 观感 / 关于 / 诊断」切；二级页内不再写与页头重复的小标题，分组靠卡间隙（锚点 s09 页层级）。

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.AppearanceScreen
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ComponentGalleryScreen
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.foundation.AnimatedScreenOverlay
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.update.ApkDownloader
import com.bsfeng.monitor.update.InstallResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateInstaller
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置一级页 + 二级页覆盖层。
 *
 * 一级页两块：快捷区（点一次就要用的两件事：无障碍服务、前台应用监控）
 * 与六组入口。两者的取舍见 [SettingsQuickAccess] / [SettingsGroupIndex] 的 KDoc。
 *
 * @param subPage 当前打开的二级页；null = 一级页。状态由 MainContent 持有，便于返回键与 Tab 切换重置
 * @param onOpenSubPage 打开二级页
 * @param onCloseSubPage 关闭二级页（系统返回键 / 页头返回按钮）
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    subPage: SettingsSubPage? = null,
    onOpenSubPage: (SettingsSubPage) -> Unit = {},
    onCloseSubPage: () -> Unit = {},
    onNavigateToSkipRules: () -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    onNavigateToAppearance: (() -> Unit)? = null,
    onNavigateToGallery: (() -> Unit)? = null,
    onNavigateToQuotaSettings: () -> Unit = {},
    /** 验证器模块入口（true = 直接落备份页）；未接线时设置卡里的两行不出现。 */
    onNavigateToTotp: ((Boolean) -> Unit)? = null,
    /** 记账「预算设置」入口；未接线时该行不带点击（不伪造跳转）。 */
    onNavigateToBudget: (() -> Unit)? = null,
) {
    // 取数与状态全在 SettingsScreenRuntime（ui/SettingsScreenRuntime.kt）；本屏只做渲染
    val rt = rememberSettingsScreenRuntime(
        viewModel = viewModel,
        onNavigateToSkipRules = onNavigateToSkipRules,
        onNavigateToWhitelist = onNavigateToWhitelist,
        onNavigateToAppearance = onNavigateToAppearance,
        onNavigateToGallery = onNavigateToGallery,
        onNavigateToQuotaSettings = onNavigateToQuotaSettings,
        onNavigateToTotp = onNavigateToTotp ?: {},
        onNavigateToBudget = onNavigateToBudget,
    )
    val t = rt.t
    val context = rt.context
    val toast = rt.toast
    val scope = rt.scope
    val demoState = rt.demoStateState.value
    val localAppearance = rt.localAppearanceState.value
    val localGallery = rt.localGalleryState.value

    // 退出动画期间 subPage 已置 null、内容仍在组合中：用非状态持有者记住最后一个二级页，
    // 避免退场那几帧取到 null 而空白（普通变量在组合期更新，不触发重组）
    val lastPage = remember { LastOpenedPage() }
    if (subPage != null) lastPage.value = subPage
    val openPage = subPage ?: lastPage.value

    // 一级页滚动位置：五态切回有数据时要回到顶部（锚点 s09-st-empty / -st-error「退出后滚动回顶部」）
    val bodyScroll = rememberScrollState()
    val headFocus = remember { FocusRequester() }
    var sawState by remember { mutableStateOf(rt.effective) }
    LaunchedEffect(rt.effective) {
        if (sawState != FiveState.POPULATED && rt.effective == FiveState.POPULATED) {
            bodyScroll.scrollTo(0)
            // 页头标题接住焦点（键盘 / 无障碍焦点不落在已被移除的空态按钮上）
            runCatching { headFocus.requestFocus() }
        }
        sawState = rt.effective
    }

    // 沉浸式：底衬画在 inset 之外（内容才吃 Scaffold 的 padding），顶部与状态栏连成一片
    Box(
        modifier
            .fillMaxSize()
            .pageBackdrop()
            .testTag("screen-s09")
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "设置",
                modifier = Modifier
                    .testTag("s09-head")
                    .focusRequester(headFocus)
                    .focusable(),
                // 版本号与「关于」页同源（SettingsAboutSection 的 TailNum(BuildConfig.VERSION_NAME)），
                // 不再写死字面量——升级后两处会不一致
                meta = "行为记录 " + BuildConfig.VERSION_NAME + " · 设备 " + viewModel.getDeviceId().take(8),
                badge = {
                    Badge(
                        text = if (rt.isPaused) "已暂停" else "运行中",
                        modifier = Modifier.testTag("s09-run-badge"),
                        tone = if (rt.isPaused) BadgeTone.Warn else BadgeTone.Success,
                    )
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("s09-body")
                    .verticalScroll(bodyScroll),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                // 演示件闸门统一走 rememberDemoVisible()（debug 构建 + 已解锁 + 调试模式已开），
                // 与「组件与状态演示」「模拟自动记账浮窗」同一判据。
                StateSwitchRow(state = demoState, onSelect = { rt.demoStateState.value = it })

                // 五态容器 + 快捷区 + 六组入口（见下）；anchored 只在一级页落五态锚点，
                // 覆盖层里的二级页不打同键（防同名节点）
                SettingsStateContainer(rt, anchored = true) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(t.space4),
                    ) {
                        SettingsQuickAccess(rt)
                        SettingsGroupIndex(rt, onOpenSubPage)
                    }
                }
                // 底部留白：导航条与悬浮操作避让（锚点 screen-s09「底部留 96dp」）
                Spacer(Modifier.height(96.dp))
            }
        }

        // 返回：下钻页（功能开关 → 细则）先回父页，一级页入口才关覆盖层。
        // 判据是页面关系（settingsParentOf），不是「上次打开过谁」——后者会跨分支串路径。
        val backFromPage: () -> Unit = {
            val parent = openPage?.let { settingsParentOf(it) }
            if (parent != null) onOpenSubPage(parent) else onCloseSubPage()
        }
        // 二级页覆盖层：一级页不卸载，返回时滚动位置保留
        AnimatedScreenOverlay(visible = subPage != null, onBack = backFromPage) {
            openPage?.let {
                SettingsSubPageScreen(
                    page = it,
                    rt = rt,
                    onBack = backFromPage,
                    onOpenSubPage = onOpenSubPage,
                )
            }
        }

        UpdateDialog(
            state = rt.currentUpdate,
            onDismiss = { rt.updateState.value = UpdateUiState.Idle },
            onDownload = { manifest ->
                rt.updateState.value = UpdateUiState.Downloading(0, 0)
                scope.launch {
                    val checker = UpdateChecker()
                    val dir = File(context.cacheDir, "updates")
                    val result = withContext(Dispatchers.IO) {
                        ApkDownloader().download(
                            sources = checker.downloadSources(manifest),
                            destDir = dir,
                            expectedSha256 = manifest.apkSha256,
                        ) { downloaded, total ->
                            rt.updateState.value = UpdateUiState.Downloading(downloaded, total)
                        }
                    }
                    result.fold(
                        onSuccess = { rt.updateState.value = UpdateUiState.Downloaded(it) },
                        onFailure = {
                            toast.show("下载失败：${it.message ?: "网络不可用"}")
                            rt.updateState.value = UpdateUiState.Idle
                        },
                    )
                }
            },
            onInstall = { apk ->
                when (val result = UpdateInstaller.install(context, apk)) {
                    is InstallResult.Started -> rt.updateState.value = UpdateUiState.Idle
                    is InstallResult.NeedPermission -> toast.show("请允许安装未知来源应用后重试")
                    is InstallResult.Failed -> toast.show(result.message)
                }
            },
        )

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )

        // 兜底桥：s13 外观（真实屏见 ui/AppearanceScreen.kt）
        AnimatedScreenOverlay(
            visible = localAppearance,
            onBack = { rt.localAppearanceState.value = false },
        ) {
            AppearanceScreen(onBack = { rt.localAppearanceState.value = false })
        }

        // 兜底桥：s12 组件与五态展示厅（真实屏见 ui/ComponentGalleryScreen.kt）
        AnimatedScreenOverlay(visible = localGallery, onBack = { rt.localGalleryState.value = false }) {
            ComponentGalleryScreen(onBack = { rt.localGalleryState.value = false })
        }
    }
}

/** 组合期更新的非状态持有者：只用于退场动画期间记住最后一个二级页。 */
private class LastOpenedPage(var value: SettingsSubPage? = null)

/**
 * 一级页快捷区：无障碍服务（点进系统设置）与前台应用监控开关。
 *
 * 为什么只有这两项：它们是每次进设置页都要确认 / 拨一次的东西 —— 无障碍是全部采集的前提、
 * 前台监控是最常用的总开关。其余配置一律收进六组入口，一级页不再平铺开关
 * （广告跳过开关只留在「功能开关」页，不在这里重复第二处）。
 */
@Composable
private fun SettingsQuickAccess(rt: SettingsScreenRuntime) {
    val t = rt.t
    val state = rt.state
    // 整行按压缩到 0.96 + LIGHT（锚点 s09-a11y）：按压基元与 clickable 共用同一 interaction；
    // 点击区仍是整行（TokenRow 不再传 onClick，避免内外两层点击各触发一次）
    val a11yInteraction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "无障碍服务",
            modifier = Modifier
                .testTag("s09-a11y")
                .pressFeedback(a11yInteraction)
                .clickable(
                    interactionSource = a11yInteraction,
                    indication = LocalIndication.current,
                ) {
                    haptic(HapticTone.LIGHT)
                    rt.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            // 两段式「已授权 · 本次启动 X」由 ui/AccessibilityCopy.kt 统一产出，本屏只传时长文案；
            // X 是**本进程**存活时长（formatUptime(processUptimeMs())），不是服务从连接起的时长
            subtitle = s09AccessibilityStatusText(
                enabled = state.accessibilityEnabled,
                running = state.accessibilityServiceRunning,
                uptimeText = formatUptime(processUptimeMs()),
            ),
            subtitleColor = if (state.accessibilityEnabled) t.successText else t.dangerText,
            icon = Icons.Outlined.Accessibility,
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "前台应用监控",
            subtitle = "今日已记录 " + state.todayEventCount + " 条事件",
            icon = settingsPageIcon(SettingsSubPage.GENERAL),
            trailing = {
                // 锚点落在开关本身：锚点 s09-monitor 的触发是「拨开关」而不是点整行
                AppSwitch(
                    checked = !rt.isPaused,
                    onCheckedChange = {
                        rt.viewModel.togglePause()
                        rt.toast.show(if (rt.isPaused) "已恢复前台应用监控" else "已暂停前台应用监控")
                    },
                    modifier = Modifier.testTag("s09-monitor"),
                    contentDescription = "前台应用监控",
                )
            },
        )
    }
}

/**
 * 一级页六组入口：一行标题 + 一行摘要 + 右侧数量/状态，点进全屏二级页。
 *
 * 「调试与诊断」由 debugUi 闸决定是否出现（release 构建恒不出现）。
 */
@Composable
private fun SettingsGroupIndex(rt: SettingsScreenRuntime, onOpen: (SettingsSubPage) -> Unit) {
    val t = rt.t
    val entries = settingsIndexEntries(rt.debugUi)
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        entries.forEachIndexed { index, page ->
            TokenRow(
                title = page.title,
                subtitle = settingsGroupSummary(page, rt),
                icon = settingsPageIcon(page),
                onClick = { onOpen(page) },
                trailing = {
                    SettingsGroupTrailing(page, rt)
                    TailChevron()
                },
            )
            TokenDivider(first = index == 0)
        }
    }
}

/** 摘要单行（TokenRow 的 subtitle 是 maxLines = 1），数量/状态放右侧尾部。 */
private fun settingsGroupSummary(page: SettingsSubPage, rt: SettingsScreenRuntime): String =
    when (page) {
        SettingsSubPage.GENERAL -> "前台监控 · 使用时长 · 事件上报 · 监控窗口 · 电池优化"
        // 摘要按 Feature 行序派生（含验证器）：手写期漏了 TOTP，用户看到的项数与实际行数不一致
        SettingsSubPage.FEATURE -> settingsFeatureSummaryText()
        SettingsSubPage.DATA -> rt.lastBackup.value?.let { "最近备份 " + formatBackupTime(it.at) }
            ?: "还没有备份 · 可立即备份或设自动频率"
        SettingsSubPage.APPEARANCE -> "主题 · 动效 · 触觉"
        SettingsSubPage.ABOUT -> "检查更新 · 开源许可"
        SettingsSubPage.DEBUG -> "调试模式 · 组件与状态演示 · 模拟自动记账浮窗"
        else -> page.meta
    }

/**
 * 单个功能当前的开关值。
 *
 * 设置页运行时把这 7 个值存成独立 Boolean（[SettingsScreenRuntime] 的 liveOn…totpOn），
 * 这里按 [com.bsfeng.monitor.feature.Feature] 逐项映射：新增功能时 when 少分支即编译失败，
 * 不会像手写计数那样静默漏项。
 */
private fun featureFlagsEnabled(rt: SettingsScreenRuntime, feature: Feature): Boolean = when (feature) {
    Feature.LIVE -> rt.liveOn
    Feature.AD_SKIP -> rt.adSkipOn
    Feature.FINANCE -> rt.financeOn
    Feature.TRANSPORT -> rt.transportOn
    Feature.QUOTA -> rt.quotaOn
    Feature.LOCATION -> rt.locationOn
    Feature.TOTP -> rt.totpOn
}

@Composable
private fun SettingsGroupTrailing(page: SettingsSubPage, rt: SettingsScreenRuntime) {
    when (page) {
        // 分母 = Feature.entries.size（7），不写字面量；此前写死 6 且漏了验证器
        SettingsSubPage.FEATURE -> TailNum(
            settingsFeatureCountText { feature -> featureFlagsEnabled(rt, feature) },
        )
        SettingsSubPage.GENERAL -> Badge(
            text = if (rt.isPaused) "已暂停" else "运行中",
            tone = if (rt.isPaused) BadgeTone.Warn else BadgeTone.Success,
        )
        SettingsSubPage.DATA -> TailNum("${rt.backupCount.value} 份")
        // 状态值直接放行尾（锚点 s09：二级页状态值不藏进副标题，一级页摘要同理）
        SettingsSubPage.APPEARANCE -> TailNum(ThemeRepository.displayName(rt.themeId))
        SettingsSubPage.ABOUT -> TailNum(BuildConfig.VERSION_NAME)
        else -> Unit
    }
}
