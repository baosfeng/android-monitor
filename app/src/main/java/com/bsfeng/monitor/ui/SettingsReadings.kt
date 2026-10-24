package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页要读的全部状态 —— 功能开关、额度设置、各 ViewModel 的 Flow、
// 动效与触觉档位、五个演示态开关、应用内更新状态、定位权限三态。
// 负责「设置页显示什么」，不含任何写操作（动作见 SettingsActions.kt）。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.rememberDebugUiAvailable
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.foundation.HapticConfig
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import com.bsfeng.monitor.ui.components.FiveState

/**
 * 设置页的全部只读状态。
 *
 * 字段都是从各模块既有数据源收集来的快照，本类不持有写入路径 ——
 * 写操作（切开关、开权限页、重读计数）集中在 [rememberSettingsActions]。
 */
@Stable
internal class SettingsReadings(
    val quotaSettings: QuotaSettings,
    val quotaAlertsOnState: MutableState<Boolean>,
    val quotaRefreshHoursState: MutableState<Int>,
    val transferViewModel: TransferViewModel?,
    val state: MonitorState,
    val isPaused: Boolean,
    val notificationsOnState: MutableState<Boolean>,
    val debugEnabled: Boolean,
    val debugUi: Boolean,
    val demoVisible: Boolean,
    val watchWindowMs: Long,
    val themeId: String,
    val motionConfig: MotionConfig,
    val motionTier: MotionTier,
    val autoDowngraded: Boolean,
    val hapticEnabled: Boolean,
    val hapticStrength: HapticStrength,
    val demoState: MutableState<FiveState>,
    val localAppearanceState: MutableState<Boolean>,
    val localGalleryState: MutableState<Boolean>,
    val updateState: MutableStateFlow<UpdateUiState>,
    val currentUpdate: UpdateUiState,
    val locationPermissionState: MutableState<LocationPermissionStatus>,
    val liveOn: Boolean,
    val adSkipOn: Boolean,
    val financeOn: Boolean,
    val transportOn: Boolean,
    val pageTraceOn: Boolean,
    val locationOn: Boolean,
    val quotaOn: Boolean,
) {
    /** 当前选了哪一段五态（与 [effective] 一起决定内容分支）。 */
    val demoStateValue: FiveState get() = demoState.value
}

/**
 * 收集设置页需要的全部状态。
 *
 * 功能开关一处读：四个功能卡与折叠逻辑都按它判定；关闭的功能不创建对应 ViewModel
 * （不建状态、不读库、不起线程）。
 */
@Composable
internal fun rememberSettingsReadings(viewModel: MainViewModel): SettingsReadings {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val featureFlags = remember { FeatureFlags.get() }
    val enabledFeatures by featureFlags.enabled.collectAsState()
    val liveOn = enabledFeatures[Feature.LIVE] == true
    val adSkipOn = enabledFeatures[Feature.AD_SKIP] == true
    val financeOn = enabledFeatures[Feature.FINANCE] == true
    val transportOn = enabledFeatures[Feature.TRANSPORT] == true
    val pageTraceOn = enabledFeatures[Feature.PAGE_TRACE] == true
    val locationOn = enabledFeatures[Feature.LOCATION] == true
    val quotaOn = enabledFeatures[Feature.QUOTA] == true
    val quotaSettings = remember { QuotaSettings(context) }
    val quotaAlertsOnState = remember { mutableStateOf(quotaSettings.alertsEnabled) }
    val quotaRefreshHoursState = remember { mutableStateOf(quotaSettings.refreshIntervalHours) }
    val transferViewModel: TransferViewModel? = if (transportOn) viewModel() else null
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
    val demoState = remember { mutableStateOf(FiveState.POPULATED) }
    // 屏内兜底桥：导航任务接线之前，setter 回调为 null 时本屏自行托管 s12 / s13，
    // 保证「外观与主题」「组件与状态演示」两个入口在真机上真的能进（接线后自动走宿主导航）。
    val localAppearanceState = remember { mutableStateOf(false) }
    val localGalleryState = remember { mutableStateOf(false) }
    // 应用内更新：检查 → 下载 → 安装（状态用 StateFlow，下载回调线程安全）
    val updateState = remember { MutableStateFlow<UpdateUiState>(UpdateUiState.Idle) }
    val currentUpdate by updateState.collectAsState()
    // 定位权限三态：进设置页与从系统设置返回时刷新（不缓存、不伪造授权状态）
    val locationPermissionState = remember { mutableStateOf(LocationCollector.get(context).status()) }
    // 通知权限（系统级）：进设置页与从系统设置返回时刷新（与 locationPermission 同一口径）
    val notificationsOnState = remember { mutableStateOf(notificationsEnabled(context)) }

    return SettingsReadings(
        quotaSettings = quotaSettings,
        quotaAlertsOnState = quotaAlertsOnState,
        quotaRefreshHoursState = quotaRefreshHoursState,
        transferViewModel = transferViewModel,
        state = state,
        isPaused = isPaused,
        notificationsOnState = notificationsOnState,
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
        demoState = demoState,
        localAppearanceState = localAppearanceState,
        localGalleryState = localGalleryState,
        updateState = updateState,
        currentUpdate = currentUpdate,
        locationPermissionState = locationPermissionState,
        liveOn = liveOn,
        adSkipOn = adSkipOn,
        financeOn = financeOn,
        transportOn = transportOn,
        pageTraceOn = pageTraceOn,
        locationOn = locationOn,
        quotaOn = quotaOn,
    )
}
