package com.bsfeng.monitor.ui

// 二级页「数据与备份」：立即备份 / 自动备份频率 / 最近一次备份，
// 以及从主卡移出的「从备份还原」——破坏性操作，单独成组 + 二次确认。
//
// 为什么还原要单独一张卡：它和「备份」不同级——备份是加法（可重复、出错无损失），
// 还原是覆盖（会替换账目与记录）。放在同一张卡里，用户扫一眼会把它当成普通一行；
// 单独成卡 + 危险色调按钮 + ConfirmDialog 三道，误触成本才够高（锚点 s09 页层级规则 3）。

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.backup.BackupConfig
import com.bsfeng.monitor.backup.BackupFrequency
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.backup.BackupOrigin
import com.bsfeng.monitor.backup.BackupOutcome
import com.bsfeng.monitor.backup.BackupScheduler
import com.bsfeng.monitor.backup.RestoreApplyOutcome
import com.bsfeng.monitor.backup.RestoreOutcome
import com.bsfeng.monitor.backup.prepareRestoreOnIo
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.foundation.HapticTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「数据与备份」页正文：备份主卡（三行）+ 还原卡（一行）+ 还原确认弹窗。
 *
 * 备份包只含本机数据文件（两张库 + shared_prefs + 规则快照），不含任何账号凭据；
 * 公共 Downloads 一份给用户自己拷走，换机 / 重装后靠它还原。
 */
@Composable
internal fun SettingsBackupSection(rt: SettingsScreenRuntime) {
    val t = rt.t
    var backupFrequency by rt.backupFrequencyState
    var backupBusy by rt.backupBusyState
    var restoreBusy by rt.restoreBusyState
    var pendingRestoreUri by rt.pendingRestoreUriState

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
                            rt.scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    BackupManager(rt.context).backupNow(BackupOrigin.MANUAL)
                                }
                                backupBusy = false
                                rt.reloadCounters()
                                when (outcome) {
                                    is BackupOutcome.Success -> rt.toast.show(
                                        "已备份：" + (outcome.publicPath ?: outcome.localName ?: ""),
                                    )
                                    is BackupOutcome.Failed -> rt.toast.show(outcome.message)
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
        TokenRow(
            title = "自动备份频率",
            subtitle = "默认每周 · 最多保留 5 份，超额删最旧",
            icon = Icons.Outlined.Timer,
            // 状态值直接放行尾（锚点 s09 页层级规则 5）
            trailing = { TailNum(backupFrequency.label) },
        )
        ChipRow(
            items = BackupFrequency.values().map { it.label },
            selectedIndex = BackupFrequency.values()
                .indexOf(backupFrequency).coerceAtLeast(0),
            onSelect = { index ->
                val frequency = BackupFrequency.values()[index]
                backupFrequency = frequency
                BackupConfig(rt.context).setFrequency(frequency)
                BackupScheduler.apply(rt.context, frequency)
                rt.view.performConfirmHaptic()
                rt.toast.show(
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
        val last = rt.lastBackup.value
        TokenRow(
            title = "最近一次备份",
            subtitle = if (last != null) "共 " + rt.backupCount.value + " 份" else "还没有备份",
            icon = Icons.Outlined.History,
            // 时间放行尾：副标题留给可复述的计数，行尾是精确时间戳
            trailing = {
                TailNum(last?.let { formatBackupTime(it.at) } ?: "—")
                TailChevron()
            },
            onClick = { rt.toast.show(last?.publicPath ?: "还没有备份包") },
        )
    }

    // 还原：破坏性操作，独立成卡（与上面的备份主卡不同级）
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "从备份还原",
            subtitle = if (restoreBusy) {
                "正在校验并还原…"
            } else {
                "选 zip 覆盖当前数据，先自动留一份 pre-restore 包"
            },
            icon = Icons.Outlined.Restore,
            onClick = {
                if (!restoreBusy) {
                    rt.restoreLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream"),
                    )
                }
            },
            trailing = {
                Badge(text = "覆盖", tone = BadgeTone.Warn)
                TailChevron()
            },
        )
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
                rt.toast.show("正在校验备份包…")
                rt.scope.launch {
                    // 准备阶段整段走 IO（prepareRestoreOnIo）：SAF 流 copyTo 落盘 → 整包 pre-restore
                    // 备份（全库打包 + sha256）→ 解包 + 全量校验，几十 MB 时留在主线就是界面冻死。
                    // 调度在 backup/RestorePreparation 里统一收口，并有单测盯住实际执行线程。
                    val outcome = prepareRestoreOnIo(rt.context) {
                        // URI 读取在 IO 线程里注入给编排层（RestoreIntake），校验失败不碰现有数据
                        openRestoreStream(rt.context, uri)
                    }
                    when (outcome) {
                        is RestoreOutcome.Rejected -> {
                            restoreBusy = false
                            rt.toast.show(outcome.reason)
                        }
                        is RestoreOutcome.Ready -> {
                            val applied = withContext(Dispatchers.IO) {
                                BackupManager(rt.context).applyRestore(outcome.plan)
                            }
                            // 这一步已 close 数据库句柄：无论成败都要重启，
                            // 失败时由下次启动的 marker 自愈回滚
                            rt.toast.show(
                                if (applied is RestoreApplyOutcome.Failed) {
                                    "还原未完成：" + applied.reason + "，App 将重启并自动回滚"
                                } else {
                                    "还原完成，App 即将重启"
                                }
                            )
                            delay(1200)
                            BackupManager(rt.context).restartProcess()
                        }
                    }
                }
            }
        },
    )
}
