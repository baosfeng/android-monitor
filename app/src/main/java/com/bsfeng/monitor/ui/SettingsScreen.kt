package com.bsfeng.monitor.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import com.bsfeng.monitor.autobook.AutoBookConfig
import com.bsfeng.monitor.quota.QuotaSettings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Key
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.BackupOrigin
import com.bsfeng.monitor.backup.BackupOutcome
import com.bsfeng.monitor.backup.BackupScheduler
import com.bsfeng.monitor.backup.RestoreApplyOutcome
import com.bsfeng.monitor.backup.RestoreOutcome
import com.bsfeng.monitor.service.HeartbeatWatchdog
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.update.ApkDownloader
import com.bsfeng.monitor.update.InstallResult
import com.bsfeng.monitor.update.UpdateCheckResult
import com.bsfeng.monitor.update.UpdateChecker
import com.bsfeng.monitor.update.UpdateInstaller
import com.bsfeng.monitor.update.UpdateManifest
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bsfeng.monitor.skip.SkipConfig
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.SkeletonBlock
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberDebugUiAvailable
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.util.DebugConfig
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.ui.theme.fgFade
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.R
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.service.TransferTileService
import com.bsfeng.monitor.transfer.service.TransferTileState
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import com.bsfeng.monitor.ui.foundation.AnimatedScreenOverlay
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.HapticConfig
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.performHaptic
import android.annotation.SuppressLint
import java.io.InputStream

/**
 * s09 设置（原型 s09-settings.html）。
 *
 * 9 个分组：监控时长窗口（chip 组）· 无障碍服务 · 自动记账 · 监控开关 · 电池优化 · 开屏广告跳过 · 外观 ·
 * 局域网互传 · 调试模式 · 关于。
 * 全部走 token；旧语义色（IOSColors / MaterialTheme 直取色）已按 LEGACY-TOKEN-MAP 换成本地 token 快照。
 *
 * 取数与状态在 [rememberSettingsScreenRuntime]；分组正文在 [SettingsSections]。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
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

                // 正文（五态容器 + 五个分组）由 SettingsSections 承载，见 ui/SettingsSections.kt
                SettingsSections(rt)
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
