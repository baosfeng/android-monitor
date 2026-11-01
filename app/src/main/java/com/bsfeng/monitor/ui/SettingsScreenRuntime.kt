package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的「取数与状态」——功能开关快照、权限/备份/更新等运行时状态、
// 三个 launcher 与三个动作函数（reloadCounters / setFeature / openLocationAppSettings）。
// 本文件只负责状态，渲染（一级页）见 ui/SettingsScreen.kt，二级页正文见 ui/SettingsSections.kt。

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.service.HeartbeatWatchdog
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.rememberDebugUiAvailable
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticConfig
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState

/**
 * 设置页运行时状态集合。
 *
 * 可写状态一律以 [MutableState] 持有：分组正文（[SettingsSections]）要按引用传递，
 * 才能在子组件里改写；只读值直接存快照。
 */

/**
 * 读取设置页所需的全部状态，并接上三个 launcher 与三个动作函数。
 *
 * 副作用集中在两处：`LifecycleResumeEffect` 在每次回到前台时刷新授权/计数，
 * `LaunchedEffect` 负责首帧取数。二者都只在首次组合与恢复时触发，不轮询。
 */
@Composable
internal fun rememberSettingsScreenRuntime(
    viewModel: MainViewModel,
    onNavigateToSkipRules: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToAppearance: (() -> Unit)?,
    onNavigateToGallery: (() -> Unit)?,
    onNavigateToQuotaSettings: () -> Unit,
    onNavigateToTotp: (Boolean) -> Unit,
): SettingsScreenRuntime {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val view = LocalView.current
    val toast = rememberToastHostState()
    val scope = rememberCoroutineScope()
    // 功能开关：一处读，四个功能卡与折叠逻辑都按它判定；关闭的功能不创建对应 ViewModel
    val featureFlags = FeatureFlags.get()
    val enabledFeatures by featureFlags.enabled.collectAsState()
    val liveOn = enabledFeatures[Feature.LIVE] == true
    val adSkipOn = enabledFeatures[Feature.AD_SKIP] == true
    val financeOn = enabledFeatures[Feature.FINANCE] == true
    val transportOn = enabledFeatures[Feature.TRANSPORT] == true
    val locationOn = enabledFeatures[Feature.LOCATION] == true
    val quotaOn = enabledFeatures[Feature.QUOTA] == true
    val totpOn = enabledFeatures[Feature.TOTP] == true
    val quotaSettings = remember { QuotaSettings(context) }
    val quotaAlertsOnState = remember { mutableStateOf(quotaSettings.alertsEnabled) }
    val quotaRefreshHoursState = remember { mutableStateOf(quotaSettings.refreshIntervalHours) }
    val transferViewModel: TransferViewModel? = if (transportOn) viewModel() else null
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    // 两层闸：debug 构建 + 关于页长按版本解锁（详见 ui/components/DebugGate.kt）
    val debugUi = rememberDebugUiAvailable()
    val demoVisible = rememberDemoVisible()
    val watchWindowMs by viewModel.watchWindowMs.collectAsState()
    val themeId by ThemeRepository.themeId.collectAsState()
    // 动效档位：选中态读 userTier（不受系统「动画时长缩放=0」覆盖），生效档位另由基元读 tier
    val motionConfig = remember(context) { MotionConfig.get(context) }
    val motionTier by motionConfig.userTier.collectAsState()
    val autoDowngraded by motionConfig.autoDowngraded.collectAsState()
    // 触觉：开关 / 强度档（改完立即生效，压测时不需要重启）
    HapticConfig.attach(context)
    val hapticEnabled by HapticConfig.enabled.collectAsState()
    val hapticStrength by HapticConfig.strength.collectAsState()
    val demoStateState = remember { mutableStateOf(FiveState.POPULATED) }
    // 屏内兜底桥：导航任务接线之前，setter 回调为 null 时本屏自行托管 s12 / s13，
    // 保证「外观与主题」「组件与状态演示」两个入口在真机上真的能进（接线后自动走宿主导航）。
    val localAppearanceState = remember { mutableStateOf(false) }
    val localGalleryState = remember { mutableStateOf(false) }
    // 应用内更新：检查 → 下载 → 安装（状态用 StateFlow，下载回调线程安全）
    val updateState = remember { MutableStateFlow<UpdateUiState>(UpdateUiState.Idle) }
    val currentUpdate by updateState.collectAsState()

    // 规则数 / 订阅源数 / 白名单数：只读其它模块的既有数据源，不新增存储
    val ruleCount = remember { mutableStateOf(0) }
    val subscriptionCount = remember { mutableStateOf(0) }
    val whitelistCount = remember { mutableStateOf(0) }
    val batteryIgnored = remember { mutableStateOf(false) }
    // 进程冻结提示（null = 心跳正常）：勾选态正常但进程没在跑时才有值
    val heartbeatNoticeState = remember { mutableStateOf<String?>(null) }
    // 通知权限（系统级）：进设置页与从系统设置返回时刷新
    val notificationsOn = remember { mutableStateOf(notificationsEnabled(context)) }
    // 定位权限三态：进设置页与从系统设置返回时刷新（不缓存、不伪造授权状态）
    val locationPermission = remember { mutableStateOf(LocationCollector.get(context).status()) }
    // 数据与备份：频率 / 最近一次 / 已存份数（进设置页即刷新，见 reloadCounters）
    val backupFrequencyState = remember { mutableStateOf(BackupConfig(context).frequency()) }
    val lastBackup = remember { mutableStateOf(BackupConfig(context).lastBackup()) }
    val backupCount = remember { mutableStateOf(0) }
    val backupBusyState = remember { mutableStateOf(false) }
    val restoreBusyState = remember { mutableStateOf(false) }
    val pendingRestoreUriState = remember { mutableStateOf<Uri?>(null) }
    // SAF 选包：OpenDocument 拿到的是可读 uri，minSdk 29 下无需申请存储权限
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUriState.value = uri
    }

    val reloadCounters: () -> Unit = rememberSettingsCounters(
        context = context,
        ruleCount = ruleCount,
        subscriptionCount = subscriptionCount,
        whitelistCount = whitelistCount,
        batteryIgnored = batteryIgnored,
        heartbeatNoticeState = heartbeatNoticeState,
        backupFrequencyState = backupFrequencyState,
        lastBackup = lastBackup,
        backupCount = backupCount,
    )

    /** 切换功能开关：落盘 + 立即生效（Tab 与设置项同一帧变化），并给一次触觉与提示。 */
    fun setFeature(feature: Feature, enabled: Boolean) {
        if (!featureFlags.setEnabled(feature, enabled)) return
        view.performConfirmHaptic()
        toast.show(if (enabled) "已开启「" + feature.title + "」" else "已关闭「" + feature.title + "」")
        // 定位开关直接决定前台服务的生死：关掉还挂着常驻通知会让用户以为采集没停
        if (feature == Feature.LOCATION) {
            if (enabled) {
                com.bsfeng.monitor.service.LocationForegroundService.start(context)
            } else {
                com.bsfeng.monitor.service.LocationForegroundService.stop(context)
            }
        }
    }

    /**
     * 跳「应用信息 → 权限」页。
     *
     * 只在「前台已授权、缺始终允许」这一态使用：Android 11+ 的后台定位不允许在弹窗里授予，
     * 必须由用户在系统设置里手动选「始终允许」。其余两态走 [locationLauncher] 的运行时申请。
     */
    fun openLocationAppSettings() {
        val opened = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (!opened) toast.show("请在系统设置 → 应用 → 权限里把位置设为「始终允许」")
    }

    val (locationLauncher, backgroundLauncher) = rememberLocationLaunchers(
        context = context,
        view = view,
        toast = toast,
        featureFlags = featureFlags,
        locationPermission = locationPermission,
        setFeature = ::setFeature,
    ).let { it.locationLauncher to it.backgroundLauncher }

    // 从系统设置页返回时立即刷新（不看轮询的运气）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
        reloadCounters()
        notificationsOn.value = notificationsEnabled(context)
        locationPermission.value = LocationCollector.get(context).status()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { reloadCounters() }

    return SettingsScreenRuntime(
        t = t,
        context = context,
        view = view,
        toast = toast,
        scope = scope,
        featureFlags = featureFlags,
        liveOn = liveOn,
        adSkipOn = adSkipOn,
        financeOn = financeOn,
        transportOn = transportOn,
        locationOn = locationOn,
        quotaOn = quotaOn,
        totpOn = totpOn,
        quotaSettings = quotaSettings,
        quotaAlertsOnState = quotaAlertsOnState,
        quotaRefreshHoursState = quotaRefreshHoursState,
        transferViewModel = transferViewModel,
        state = state,
        isPaused = isPaused,
        debugEnabled = debugEnabled,
        debugUi = debugUi,
        demoVisible = demoVisible,
        watchWindowMs = watchWindowMs,
        themeId = themeId,
        motionConfig = motionConfig,
        motionTier = motionTier,
        autoDowngraded = autoDowngraded,
        hapticEnabled = hapticEnabled,
        hapticStrength = hapticStrength,
        demoStateState = demoStateState,
        localAppearanceState = localAppearanceState,
        localGalleryState = localGalleryState,
        updateState = updateState,
        currentUpdate = currentUpdate,
        ruleCount = ruleCount,
        subscriptionCount = subscriptionCount,
        whitelistCount = whitelistCount,
        batteryIgnored = batteryIgnored,
        heartbeatNoticeState = heartbeatNoticeState,
        notificationsOn = notificationsOn,
        locationPermission = locationPermission,
        backupFrequencyState = backupFrequencyState,
        lastBackup = lastBackup,
        backupCount = backupCount,
        backupBusyState = backupBusyState,
        restoreBusyState = restoreBusyState,
        pendingRestoreUriState = pendingRestoreUriState,
        restoreLauncher = restoreLauncher,
        locationLauncher = locationLauncher,
        backgroundLauncher = backgroundLauncher,
        reloadCounters = reloadCounters,
        setFeature = ::setFeature,
        openLocationAppSettings = ::openLocationAppSettings,
        viewModel = viewModel,
        effective = if (demoStateState.value == FiveState.POPULATED) {
            FiveState.POPULATED
        } else {
            demoStateState.value
        },
        onNavigateToSkipRules = onNavigateToSkipRules,
        onNavigateToWhitelist = onNavigateToWhitelist,
        onNavigateToAppearance = onNavigateToAppearance,
        onNavigateToGallery = onNavigateToGallery,
        onNavigateToQuotaSettings = onNavigateToQuotaSettings,
        onNavigateToTotp = onNavigateToTotp,
    )
}
