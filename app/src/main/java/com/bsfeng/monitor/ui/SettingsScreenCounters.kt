package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置页的「取数」——规则数 / 订阅源数 / 白名单数 / 电池优化 /
// 进程冻结提示 / 备份频率与份数。只读既有数据源，不新增存储。

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.service.HeartbeatWatchdog

/**
 * 刷新设置页上的一批只读计数。
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
): () -> Unit = remember(context) {
    {
        ruleCount.value = MonitorApp.instance.container.skipRuleManager.getAllRules().size
        subscriptionCount.value = MonitorApp.instance.container.skipRuleManager.subscriptionMetas().size
        whitelistCount.value = context
            .getSharedPreferences("skip_whitelist", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet())
            ?.size ?: 0
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        batteryIgnored.value = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        heartbeatNoticeState.value = HeartbeatWatchdog.gapNotice(context)
        val backupConfig = BackupConfig(context)
        backupFrequencyState.value = backupConfig.frequency()
        lastBackup.value = backupConfig.lastBackup()
        backupCount.value = BackupManager(context).listBackups().size
    }
}
