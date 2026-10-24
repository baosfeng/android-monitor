package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页运行时状态的持有者。字段一一对应屏内用到的状态，
// 可写项以 MutableState 传递，让二级页正文（SettingsSubPageScreen）能按引用改写。

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 设置页运行时状态集合。
 *
 * 可写状态一律以 [MutableState] 持有：二级页正文（[SettingsSubPageScreen]）要按引用传递，
 * 才能在子组件里改写；只读值直接存快照。
 */
internal class SettingsScreenRuntime(
    val t: DesignTokens,
    val context: Context,
    val view: View,
    val toast: ToastHostState,
    val scope: CoroutineScope,
    val featureFlags: FeatureFlags,
    val liveOn: Boolean,
    val adSkipOn: Boolean,
    val financeOn: Boolean,
    val transportOn: Boolean,
    val locationOn: Boolean,
    val quotaOn: Boolean,
    val quotaSettings: QuotaSettings,
    val quotaAlertsOnState: MutableState<Boolean>,
    val quotaRefreshHoursState: MutableState<Int>,
    val transferViewModel: TransferViewModel?,
    val state: MonitorState,
    val isPaused: Boolean,
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
    val demoStateState: MutableState<FiveState>,
    val localAppearanceState: MutableState<Boolean>,
    val localGalleryState: MutableState<Boolean>,
    val updateState: MutableStateFlow<UpdateUiState>,
    val currentUpdate: UpdateUiState,
    val ruleCount: MutableState<Int>,
    val subscriptionCount: MutableState<Int>,
    val whitelistCount: MutableState<Int>,
    val batteryIgnored: MutableState<Boolean>,
    val heartbeatNoticeState: MutableState<String?>,
    val notificationsOn: MutableState<Boolean>,
    val locationPermission: MutableState<LocationPermissionStatus>,
    val backupFrequencyState: MutableState<BackupFrequency>,
    val lastBackup: MutableState<LastBackup?>,
    val backupCount: MutableState<Int>,
    val backupBusyState: MutableState<Boolean>,
    val restoreBusyState: MutableState<Boolean>,
    val pendingRestoreUriState: MutableState<Uri?>,
    val restoreLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val locationLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    val backgroundLauncher: ManagedActivityResultLauncher<String, Boolean>,
    val reloadCounters: () -> Unit,
    val setFeature: (Feature, Boolean) -> Unit,
    val openLocationAppSettings: () -> Unit,
    val viewModel: MainViewModel,
    val effective: FiveState,
    val onNavigateToSkipRules: () -> Unit,
    val onNavigateToWhitelist: () -> Unit,
    val onNavigateToAppearance: (() -> Unit)?,
    val onNavigateToGallery: (() -> Unit)?,
    val onNavigateToQuotaSettings: () -> Unit,
)
