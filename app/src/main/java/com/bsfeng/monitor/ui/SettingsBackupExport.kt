package com.bsfeng.monitor.ui

// 「导出到下载目录」的整条链路：入口行 + 「选一份」弹窗 + 未加密包的破坏性确认。
// 单独成文件的原因：它自带三态（选择 / 待确认 / 复制中）与两个弹窗，塞回
// SettingsDataSection 会把那个文件顶过 300 行（拆文件口径见 docs/开发指南/代码规范.md），
// 而它与「备份 / 还原」的耦合只有一个入口行 —— 边界清楚，值得单独放。
//
// 分工：备份只写 App 私有目录（不加密的包不落公共目录），导出是唯一把它搬出设备的路，
// 因此必须是「用户挑一份 → 破坏性确认 → 复制一份」；不自动、不定时、不动原文件。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.backup.BackupExportGate
import com.bsfeng.monitor.backup.BackupFileInfo
import com.bsfeng.monitor.backup.BackupManager
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选择列表的最大高度：约 4.5 行，超出滚动 —— 弹窗不把整屏吃满（同 DIALOG_MAX_W 的口径）。 */
private val EXPORT_PICKER_MAX_H = 320.dp

/**
 * 「导出到下载目录」入口 + 两个弹窗（在备份主卡里当一行用）。
 *
 * 三态由本组件自己持有：选择弹窗是否打开 / 待确认的文件名（null = 无）/ 是否正在复制。
 * 确认闸门用 [BackupExportGate]：只有用户越过确认之后，才拿得到可写盘的文件名。
 */
@Composable
internal fun BackupExportFlow(rt: SettingsScreenRuntime) {
    val exportGate = remember { BackupExportGate() }
    var pickerOpen by remember { mutableStateOf(false) }
    var pendingName by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<BackupFileInfo>>(emptyList()) }
    var exportBusy by remember { mutableStateOf(false) }

    // 列目录是磁盘 IO：跟着弹窗开关走 IO 线程，不在首帧读盘（同 reloadCounters 的口径）
    LaunchedEffect(pickerOpen) {
        if (pickerOpen) {
            files = withContext(Dispatchers.IO) { BackupManager(rt.context).listBackups() }
        }
    }

    TokenDivider(first = false)
    TokenRow(
        title = "导出到下载目录",
        // 一句话说清代价：包不加密、要落公共目录、所以必须挑一份再确认
        subtitle = if (exportBusy) "正在复制…" else "挑一份复制到 Downloads（未加密，需确认）",
        icon = Icons.Outlined.FileDownload,
        onClick = { if (!exportBusy) pickerOpen = true },
        trailing = { TailChevron() },
    )

    // ① 先挑一份（「用户显式选择某一份」）；② 再越一次破坏性确认；③ 才复制
    if (pickerOpen) {
        BackupExportPicker(
            files = files,
            onDismiss = { pickerOpen = false },
            onPick = { file ->
                pickerOpen = false
                if (exportGate.request(file.name)) {
                    pendingName = exportGate.pendingName
                } else {
                    rt.toast.show("这份备份没有可导出的文件名")
                }
            },
        )
    }

    ConfirmDialog(
        visible = pendingName != null,
        title = "导出到下载目录",
        message = "导出的是未加密备份包：含真实账目、使用记录与轨迹坐标。" +
            "复制到公共 Downloads 后，同机任何文件管理器与网盘同步都可能读到。" +
            "原备份仍留在 App 私有目录。",
        // 破坏性语义：确认键走 danger + REJECT，且不与取消键争权重（见 ConfirmDialog 注释）
        confirmLabel = "仍然导出",
        dismissLabel = "取消",
        destructive = true,
        onDismiss = {
            exportGate.cancel()
            pendingName = null
        },
        onConfirm = {
            pendingName = null
            // 闸门在这里给出唯一合法的文件名：没确认过（或刚被取消）时是 null，就什么都不写
            val name = exportGate.confirm()
            if (name != null && !exportBusy) {
                exportBusy = true
                rt.scope.launch {
                    val path = withContext(Dispatchers.IO) {
                        BackupManager(rt.context).exportBackup(name)
                    }
                    exportBusy = false
                    rt.toast.show(path?.let { "已导出到 " + it } ?: "导出失败，备份仍在 App 私有目录")
                }
            }
        },
    )
}

/**
 * 导出前的备份选择弹窗（原型 .dialog 体系，不新造样式）。
 *
 * 列表与「共 N 份」、还原入口同源（[BackupManager.listBackups]），点一行 = 选定并关闭，
 * 随后才出现破坏性确认。空列表时给一句说明，不留白面板。
 */
@Composable
private fun BackupExportPicker(
    files: List<BackupFileInfo>,
    onDismiss: () -> Unit,
    onPick: (BackupFileInfo) -> Unit,
) {
    val t = LocalDesignTokens.current
    AppDialog(visible = true, onDismiss = onDismiss) {
        Text("选择要导出的备份", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        if (files.isEmpty()) {
            Text("还没有备份，先点「立即备份」", style = MaterialTheme.typography.bodySmall, color = t.muted)
        } else {
            Column(
                Modifier
                    .heightIn(max = EXPORT_PICKER_MAX_H)
                    .verticalScroll(rememberScrollState()),
            ) {
                files.forEach { file ->
                    SettingsRow(
                        title = file.name,
                        subtitle = formatBackupTime(file.modifiedAt),
                        onClick = { onPick(file) },
                    )
                }
            }
        }
        Spacer(Modifier.height(t.space3))
        AppButton(
            text = "取消",
            onClick = onDismiss,
            variant = AppButtonVariant.Ghost,
            block = true,
        )
    }
}
