package com.bsfeng.monitor.ui

// 设置（原型 s09-settings.html）：一级页 = 常用快捷项 + 四组入口（功能 / 通用 / 数据与备份 /
// 关于与调试），点进全屏二级页；二级页正文与五态容器见 ui/SettingsSections.kt。
// 取数与状态在 ui/SettingsScreenRuntime.kt；本屏只做一级页渲染与覆盖层接线。
//
// 一级页始终留在组合树里、二级页以覆盖层叠加：返回时一级页的滚动位置天然保留。
// 二级页状态由 MainContent 持有（rememberSaveable + 枚举名），旋转 / 进程重建可恢复。

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.update.ApkDownloader
import com.bsfeng.monitor.update.InstallResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateInstaller
import com.bsfeng.monitor.update.UpdateManifest
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置一级页 + 二级页覆盖层。
 *
 * 一级页固定两块：常用快捷项（无障碍服务状态 / 开屏广告跳过开关）与四个入口分组。
 * 暂停状态徽标留在页头；无障碍摘要留在快捷项副标题（同一数据源，见 ui/AccessibilityCopy.kt）。
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
) {
    // 取数与状态全在 SettingsScreenRuntime（ui/SettingsScreenRuntime.kt）；本屏只做渲染
    val rt = rememberSettingsScreenRuntime(
        viewModel = viewModel,
        onNavigateToSkipRules = onNavigateToSkipRules,
        onNavigateToWhitelist = onNavigateToWhitelist,
        onNavigateToAppearance = onNavigateToAppearance,
        onNavigateToGallery = onNavigateToGallery,
        onNavigateToQuotaSettings = onNavigateToQuotaSettings,
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

    // 沉浸式：底衬画在 inset 之外（内容才吃 Scaffold 的 padding），顶部与状态栏连成一片
    Box(
        modifier
            .fillMaxSize()
            .pageBackdrop()
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "设置",
                meta = "行为记录 1.0.0 · 设备 " + viewModel.getDeviceId().take(8),
                badge = {
                    Badge(
                        text = if (rt.isPaused) "已暂停" else "运行中",
                        tone = if (rt.isPaused) BadgeTone.Warn else BadgeTone.Success,
                    )
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(t.space4),
            ) {
                StateSwitchRow(state = demoState, onSelect = { rt.demoStateState.value = it })

                // 五态容器 + 快捷项 + 四组入口（见 ui/SettingsSections.kt）
                SettingsStateContainer(rt) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(t.space4),
                    ) {
                        SettingsQuickAccess(rt)
                        SettingsGroupIndex(rt, onOpenSubPage)
                    }
                }
            }
        }

        // 二级页覆盖层：一级页不卸载，返回时滚动位置保留
        AnimatedScreenOverlay(visible = subPage != null, onBack = onCloseSubPage) {
            openPage?.let { SettingsSubPageScreen(page = it, rt = rt, onBack = onCloseSubPage) }
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
 * 一级页常用快捷项：无障碍服务状态（点进系统设置）与开屏广告跳过开关。
 *
 * 这两项是设置页最高频的两个动作，留在二级页外可省一次跳转；其余配置项全部收进四组入口。
 */
@Composable
private fun SettingsQuickAccess(rt: SettingsScreenRuntime) {
    val t = rt.t
    val state = rt.state
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "无障碍服务",
            // 两段式「已授权 · 已运行 X」由 ui/AccessibilityCopy.kt 统一产出，本屏只传时长文案
            subtitle = s09AccessibilityStatusText(
                enabled = state.accessibilityEnabled,
                running = state.accessibilityServiceRunning,
                uptimeText = formatUptime(processUptimeMs()),
            ),
            subtitleColor = if (state.accessibilityEnabled) t.successText else t.dangerText,
            icon = Icons.Outlined.Accessibility,
            onClick = { rt.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "开屏广告跳过",
            subtitle = if (rt.adSkipOn) "已开启 · 识别到开屏广告自动跳过" else "已关闭 · 不生成「广告」Tab",
            icon = Icons.Outlined.PlayArrow,
            trailing = {
                AppSwitch(
                    checked = rt.adSkipOn,
                    onCheckedChange = { rt.setFeature(Feature.AD_SKIP, it) },
                )
            },
        )
    }
}

/** 一级页四个入口：一行标题 + 一行摘要 + 右侧数量/状态，点进全屏二级页。 */
@Composable
private fun SettingsGroupIndex(rt: SettingsScreenRuntime, onOpen: (SettingsSubPage) -> Unit) {
    val t = rt.t
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        SettingsSubPage.entries.forEachIndexed { index, page ->
            TokenRow(
                title = page.title,
                subtitle = settingsGroupSummary(page, rt),
                icon = settingsGroupIcon(page),
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

private fun settingsGroupIcon(page: SettingsSubPage): ImageVector = when (page) {
    SettingsSubPage.FEATURE -> Icons.Outlined.Tune
    SettingsSubPage.GENERAL -> Icons.Outlined.Settings
    SettingsSubPage.DATA -> Icons.Outlined.Save
    SettingsSubPage.ABOUT -> Icons.Outlined.Info
}

/** 摘要单行（TokenRow 的 subtitle 是 maxLines = 1），数量/状态放右侧尾部。 */
private fun settingsGroupSummary(page: SettingsSubPage, rt: SettingsScreenRuntime): String = when (page) {
    SettingsSubPage.FEATURE -> "实时 / 广告跳过 / 记账 / 传送 / 额度 / 页面轨迹 / 位置"
    SettingsSubPage.GENERAL -> "无障碍 · 监控开关 · 电池优化 · 通知 · 外观与动效"
    SettingsSubPage.DATA -> rt.lastBackup.value?.let { "最近备份 " + formatBackupTime(it.at) }
        ?: "还没有备份 · 可立即备份或设自动频率"
    SettingsSubPage.ABOUT -> "检查更新 · 开源许可 · 调试与组件库"
}

@Composable
private fun SettingsGroupTrailing(page: SettingsSubPage, rt: SettingsScreenRuntime) {
    when (page) {
        SettingsSubPage.FEATURE -> {
            val on = listOf(
                rt.liveOn, rt.adSkipOn, rt.financeOn, rt.transportOn,
                rt.quotaOn, rt.locationOn,
            ).count { it }
            TailNum("开 $on / 6")
        }
        SettingsSubPage.GENERAL -> Badge(
            text = if (rt.isPaused) "已暂停" else "运行中",
            tone = if (rt.isPaused) BadgeTone.Warn else BadgeTone.Success,
        )
        SettingsSubPage.DATA -> TailNum("${rt.backupCount.value} 份")
        SettingsSubPage.ABOUT -> TailNum(BuildConfig.VERSION_NAME)
    }
}
