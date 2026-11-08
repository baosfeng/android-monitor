package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的「取数」——规则数 / 订阅源数 / 白名单数 / 电池优化 /
// 进程冻结提示 / 备份频率与份数。只读既有数据源，不新增存储。

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.service.HeartbeatWatchdog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页只读计数的一次取数结果（一次性快照，避免回写时字段错位）。 */
internal data class SettingsCountersSnapshot(
    val ruleCount: Int,
    val subscriptionCount: Int,
    val whitelistCount: Int,
    val batteryIgnored: Boolean,
    val heartbeatNotice: String?,
    val backupFrequency: BackupFrequency,
    val lastBackup: LastBackup?,
    val backupCount: Int,
)

/**
 * 取一次设置页计数。
 *
 * 必须整体跑在 IO：`skipRuleManager.getAllRules()` 读规则文件并解析 JSON、
 * `BackupManager.listBackups()` 列目录，订阅源规则多时会在主线程卡住首帧
 * （调用方是 `LifecycleResumeEffect` / `LaunchedEffect`）。
 * [ioDispatcher] 只为单测能断言「取数确实发生在 IO 调度器」。
 */
internal suspend fun loadSettingsCounters(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): SettingsCountersSnapshot {
    val appContext = context.applicationContext
    return withContext(ioDispatcher) {
        val rules = MonitorApp.instance.container.skipRuleManager
        val backupConfig = BackupConfig(appContext)
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        SettingsCountersSnapshot(
            ruleCount = rules.getAllRules().size,
            subscriptionCount = rules.subscriptionMetas().size,
            whitelistCount = appContext
                .getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)
                .getStringSet("whitelist", emptySet())
                ?.size ?: 0,
            batteryIgnored = pm?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false,
            heartbeatNotice = HeartbeatWatchdog.gapNotice(appContext),
            backupFrequency = backupConfig.frequency(),
            lastBackup = backupConfig.lastBackup(),
            backupCount = BackupManager(appContext).listBackups().size,
        )
    }
}

/**
 * 刷新设置页上的一批只读计数。
 *
 * 取数在 [loadSettingsCounters] 里切到 IO；回写由 `rememberCoroutineScope()`（Main）
 * 完成，调用方仍是普通 `() -> Unit`，生命周期由组合作用域兜住（离屏即取消）。
 *
 * 进程冻结只能事后发现（用户打开 App 即解冻），所以冻结提示展示「上次中断了多久」，
 * 而不是「此刻是否停更」。
 */
@Composable
internal fun rememberSettingsCounters(
    context: Context,
    ruleCount: MutableState<Int>,
    subscriptionCount: MutableState<Int>,
    whitelistCount: MutableState<Int>,
    batteryIgnored: MutableState<Boolean>,
    heartbeatNoticeState: MutableState<String?>,
    backupFrequencyState: MutableState<BackupFrequency>,
    lastBackup: MutableState<LastBackup?>,
    backupCount: MutableState<Int>,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext
    return remember(context) {
        {
            scope.launch {
                val counters = loadSettingsCounters(appContext)
                ruleCount.value = counters.ruleCount
                subscriptionCount.value = counters.subscriptionCount
                whitelistCount.value = counters.whitelistCount
                batteryIgnored.value = counters.batteryIgnored
                heartbeatNoticeState.value = counters.heartbeatNotice
                backupFrequencyState.value = counters.backupFrequency
                lastBackup.value = counters.lastBackup
                backupCount.value = counters.backupCount
            }
        }
    }
}
