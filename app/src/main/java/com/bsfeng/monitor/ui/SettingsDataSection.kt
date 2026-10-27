package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置「数据」分组 —— 立即备份 / 从备份还原 / 自动备份频率 / 最近一次备份，以及还原确认弹窗。负责「本地数据怎么打包与还原」。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.LastBackup
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.BackupOrigin
import com.bsfeng.monitor.backup.BackupOutcome
import com.bsfeng.monitor.backup.BackupScheduler
import com.bsfeng.monitor.backup.RestoreApplyOutcome
import com.bsfeng.monitor.backup.RestoreOutcome
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.foundation.HapticTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.view.View
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.bsfeng.monitor.ui.components.ToastHostState
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 设置「数据」分组 —— 立即备份 / 从备份还原 / 自动备份频率 / 最近一次备份，以及还原确认弹窗。负责「本地数据怎么打包与还原」。
 */
@Composable
internal fun SettingsDataSection(
    t: DesignTokens,
    context: Context,
    view: View,
    toast: ToastHostState,
    scope: CoroutineScope,
    backupFrequencyState: MutableState<BackupFrequency>,
    lastBackup: LastBackup?,
    backupCount: Int,
    backupBusyState: MutableState<Boolean>,
    restoreBusyState: MutableState<Boolean>,
    pendingRestoreUriState: MutableState<Uri?>,
    restoreLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    reloadCounters: () -> Unit,
) {
    var backupFrequency by backupFrequencyState
    var backupBusy by backupBusyState
    var restoreBusy by restoreBusyState
    var pendingRestoreUri by pendingRestoreUriState

// ===== 数据：数据与备份 =====
SectionHeader("数据")
// ⑧ 数据与备份（立即备份 / 从备份还原 / 自动频率 / 最近一次）
// 备份包只含本机数据文件（两张库 + shared_prefs + 规则快照），不含任何账号凭据；
// 公共 Downloads 一份给用户自己拷走，换机 / 重装后靠它还原。
Column(Modifier.fillMaxWidth()) {
    GroupLabel("数据与备份")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "立即备份",
            subtitle = if (backupBusy) "正在打包账目与记录…" else "打包账目、记录与设置到 Downloads",
            icon = Icons.Outlined.Save,
            trailing = {
                AppButton(
                    text = if (backupBusy) "备份中" else "备份",
                    onClick = {
                        if (!backupBusy) {
                            backupBusy = true
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    BackupManager(context).backupNow(BackupOrigin.MANUAL)
                                }
                                backupBusy = false
                                reloadCounters()
                                when (outcome) {
                                    is BackupOutcome.Success -> toast.show(
                                        "已备份：" + (outcome.publicPath ?: outcome.localName ?: ""),
                                    )
                                    is BackupOutcome.Failed -> toast.show(outcome.message)
                                }
                            }
                        }
                    },
                    variant = AppButtonVariant.Ghost,
                    enabled = !backupBusy,
                    tone = HapticTone.SUCCESS,
                )
            },
        )
        TokenDivider(first = false)
        // 还原走 SAF：选包 → 校验 sha256 → 自动留 pre-restore 包 → 替换文件 → 重启进程
        TokenRow(
            title = "从备份还原",
            subtitle = if (restoreBusy) "正在校验并还原…" else "选 zip 覆盖当前数据，先自动留一份",
            icon = Icons.Outlined.Restore,
            onClick = {
                if (!restoreBusy) {
                    restoreLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream"),
                    )
                }
            },
            trailing = { TailChevron() },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "自动备份频率",
            subtitle = "默认每周 · 最多保留 5 份，超额删最旧",
            icon = Icons.Outlined.Timer,
        )
        ChipRow(
            items = BackupFrequency.values().map { it.label },
            selectedIndex = BackupFrequency.values()
                .indexOf(backupFrequency).coerceAtLeast(0),
            onSelect = { index ->
                val frequency = BackupFrequency.values()[index]
                backupFrequency = frequency
                BackupConfig(context).setFrequency(frequency)
                BackupScheduler.apply(context, frequency)
                view.performConfirmHaptic()
                toast.show(
                    if (frequency == BackupFrequency.OFF) {
                        "已关闭自动备份"
                    } else {
                        "自动备份：" + frequency.label + "备份一次"
                    },
                )
            },
            modifier = Modifier.padding(
                horizontal = t.space4,
                vertical = t.space2,
            ),
        )
        TokenDivider(first = false)
        TokenRow(
            title = "最近一次备份",
            subtitle = lastBackup?.let {
                formatBackupTime(it.at) + " · 共 " + backupCount + " 份"
            } ?: "还没有备份",
            icon = Icons.Outlined.History,
            onClick = {
                toast.show(lastBackup?.publicPath ?: "还没有备份包")
            },
            trailing = { TailChevron() },
        )
        Spacer(Modifier.height(t.space1))
    }
    ConfirmDialog(
        visible = pendingRestoreUri != null,
        title = "从备份还原",
        message = "将用所选备份覆盖当前的账目、记录与设置。还原前会自动把当前数据" +
            "备份成一份 pre-restore 包；还原完成后 App 会自动重启。",
        confirmLabel = "还原",
        dismissLabel = "取消",
        onDismiss = { pendingRestoreUri = null },
        onConfirm = {
            val uri = pendingRestoreUri
            pendingRestoreUri = null
            if (uri != null && !restoreBusy) {
                restoreBusy = true
                toast.show("正在校验备份包…")
                scope.launch {
                    // URI 读取在 IO 线程里注入给编排层（RestoreIntake），校验失败不碰现有数据
                    val outcome = BackupManager(context).prepareRestore {
                        openRestoreStream(context, uri)
                    }
                    when (outcome) {
                        is RestoreOutcome.Rejected -> {
                            restoreBusy = false
                            toast.show(outcome.reason)
                        }
                        is RestoreOutcome.Ready -> {
                            val applied = withContext(Dispatchers.IO) {
                                BackupManager(context).applyRestore(outcome.plan)
                            }
                            // 这一步已 close 数据库句柄：无论成败都要重启，
                            // 失败时由下次启动的 marker 自愈回滚
                            toast.show(
                                if (applied is RestoreApplyOutcome.Failed) {
                                    "还原未完成：" + applied.reason + "，App 将重启并自动回滚"
                                } else {
                                    "还原完成，App 即将重启"
                                }
                            )
                            delay(1200)
                            BackupManager(context).restartProcess()
                        }
                    }
                }
            }
        },
    )
}
}
