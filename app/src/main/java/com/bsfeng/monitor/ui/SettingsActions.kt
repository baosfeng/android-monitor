package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页能触发的动作 —— 切功能开关（落盘 + 触觉 + 提示）、
// 跳「应用信息 → 权限」、两个定位权限 launcher、重读计数器。负责「点一下发生什么」。

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.service.HeartbeatWatchdog
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.performConfirmHaptic

/**
 * 设置页要展示的只读计数（每次进页 / 从系统设置返回时重算，不缓存）。
 *
 * 规则数 / 订阅源数 / 白名单数只读其它模块的既有数据源，不新增存储。
 */
@Stable
internal class SettingsCounters {
    val ruleCount = mutableStateOf(0)
    val subscriptionCount = mutableStateOf(0)
    val whitelistCount = mutableStateOf(0)
    val batteryIgnored = mutableStateOf(false)

    /** 进程冻结提示（null = 心跳正常）：勾选态正常但进程没在跑时才有值。 */
    val heartbeatNoticeState = mutableStateOf<String?>(null)
    val backupFrequencyState = mutableStateOf(BackupFrequency.OFF)
    val lastBackup = mutableStateOf<LastBackup?>(null)
    val backupCount = mutableStateOf(0)
    val backupBusyState = mutableStateOf(false)
    val restoreBusyState = mutableStateOf(false)
    val pendingRestoreUriState = mutableStateOf<Uri?>(null)
}

/**
 * 重读设置页的全部计数。
 *
 * 进程冻结提示读心跳中断记录 —— 冻结只能事后发现（用户打开 App 即解冻），
 * 所以展示「上次中断了多久」，而不是「此刻是否停更」。
 */
internal fun refreshSettingsCounters(context: Context, counters: SettingsCounters) {
    counters.ruleCount.value = MonitorApp.instance.container.skipRuleManager.getAllRules().size
    counters.subscriptionCount.value =
        MonitorApp.instance.container.skipRuleManager.subscriptionMetas().size
    counters.whitelistCount.value = context
        .getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)
        .getStringSet("whitelist", emptySet())
        ?.size ?: 0
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    counters.batteryIgnored.value = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    counters.heartbeatNoticeState.value = HeartbeatWatchdog.gapNotice(context)
    val backupConfig = BackupConfig(context)
    counters.backupFrequencyState.value = backupConfig.frequency()
    counters.lastBackup.value = backupConfig.lastBackup()
    counters.backupCount.value = BackupManager(context).listBackups().size
}

/** 设置页的动作句柄：屏幕只按引用调用，不关心实现。 */
@Stable
internal class SettingsActions(
    val reloadCounters: () -> Unit,
    val setFeature: (Feature, Boolean) -> Unit,
    val openLocationAppSettings: () -> Unit,
    val locationLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    val backgroundLauncher: ManagedActivityResultLauncher<String, Boolean>,
    val restoreLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
)

/**
 * 构造设置页的动作集合。
 *
 * @param locationPermission 定位权限三态（回调里回写真实状态，不按「点了同意」假设已授权）
 */
@Composable
internal fun rememberSettingsActions(
    counters: SettingsCounters,
    locationPermission: MutableState<LocationPermissionStatus>,
    toast: ToastHostState,
): SettingsActions {
    val context = LocalContext.current
    val view = LocalView.current
    val featureFlags = remember { FeatureFlags.get() }

    fun reloadCounters() = refreshSettingsCounters(context, counters)

    /** 切换功能开关：落盘 + 立即生效（Tab 与设置项同一帧变化），并给一次触觉与提示。 */
    fun setFeature(feature: Feature, enabled: Boolean) {
        if (!featureFlags.setEnabled(feature, enabled)) return
        view.performConfirmHaptic()
        toast.show(if (enabled) "已开启「" + feature.title + "」" else "已关闭「" + feature.title + "」")
    }

    /**
     * 跳「应用信息 → 权限」页。
     *
     * 只在「前台已授权、缺始终允许」这一态使用：Android 11+ 的后台定位不允许在弹窗里授予，
     * 必须由用户在系统设置里手动选「始终允许」。其余两态走 locationLauncher 的运行时申请。
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

    /**
     * 定位权限申请（运行时授权）。
     *
     * 为什么必须先申请：Android 上**从未 requestPermissions 过**的权限不会出现在
     * 「应用信息 → 权限」列表里，只跳系统设置用户根本找不到该权限项 —— 真机缺陷的根因。
     *
     * FINE 与 COARSE 必须同批申请：Android 12+ 只申请 FINE 会被系统直接忽略。
     * 后台定位（ACCESS_BACKGROUND_LOCATION）不进这一批：Android 11+ 要求单独引导，
     * 只能由用户在系统设置里选「始终允许」，所以回调后按三态决定是否露出那条引导行。
     */
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 先回读真实授权状态，再决定开关：不按「点了同意」假设已授权
        val status = LocationCollector.get(context).status()
        locationPermission.value = status
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            status != LocationPermissionStatus.NOT_GRANTED
        if (!granted) {
            // 分支①：都拒绝（含「不再询问」）→ 开关保持/回到关闭，如实说明未授权。
            // 这里不用 setFeature：开关此刻在 UI 上仍是关，setFeature 会因「值没变」提前 return，
            // 提示就弹不出来（用户会以为点了没反应）。
            if (featureFlags.setEnabled(Feature.LOCATION, false)) view.performConfirmHaptic()
            toast.show("未授权，无法记录位置 · 可在系统设置开启")
        } else {
            // 分支②③：只给「仅使用期间」或已满足 → 开关打开；缺后台定位时引导行自动出现
            setFeature(Feature.LOCATION, true)
        }
    }

    /**
     * 后台定位单独申请（只有这一条路能让系统弹出「始终允许」）。
     *
     * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 落到的是应用信息**主页**，位置项里只有
     * 「仅在使用中允许」；Android 11+ 必须**单独一次** requestPermissions(ACCESS_BACKGROUND_LOCATION)，
     * 系统才会打开「允许在后台访问位置」那个专门页面（始终 / 仅使用中 / 拒绝三选项）。
     *
     * ⚠️ 不能和 FINE/COARSE 同批：混在一起时系统会直接忽略后台这一项。
     */
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 不按回调布尔值下结论：部分 ROM 对「仅本次允许」也回 true。回读系统真实状态。
        val status = LocationCollector.get(context).status()
        locationPermission.value = status
        if (status != LocationPermissionStatus.GRANTED) {
            toast.show("仍缺「始终允许」：设置 → 应用 → 权限 → 位置 → 始终允许")
        }
        // 已授予时不做额外动作：状态刷新后该行自然变成「已授权」，无需再提示
    }

    // SAF 选包：OpenDocument 拿到的是可读 uri，minSdk 29 下无需申请存储权限
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) counters.pendingRestoreUriState.value = uri
    }

    return SettingsActions(
        reloadCounters = ::reloadCounters,
        setFeature = ::setFeature,
        openLocationAppSettings = ::openLocationAppSettings,
        locationLauncher = locationLauncher,
        backgroundLauncher = backgroundLauncher,
        restoreLauncher = restoreLauncher,
    )
}
