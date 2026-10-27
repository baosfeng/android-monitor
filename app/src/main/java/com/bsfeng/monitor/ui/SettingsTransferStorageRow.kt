package com.bsfeng.monitor.ui

// 从 ui/SettingsTransferCard.kt 拆出：接收目录一行（占用 / 文件数 / 清理入口）与两个确认弹窗 ——
// 「本机名称」改名的输入框、清理接收目录的二次确认。负责「接收到的文件看得到、清得掉」。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.theme.DesignTokens
import kotlinx.coroutines.CoroutineScope

/**
 * 接收目录：占用统计 + 清理入口 + 两个确认弹窗（改名 / 清理）。
 */
@Composable
internal fun SettingsTransferStorageRow(
    t: DesignTokens,
    toast: ToastHostState,
    scope: CoroutineScope,
    view: View,
    transferViewModel: TransferViewModel,
    stats: DownloadsSink.ReceivedStats,
    nameDraftState: MutableState<String>,
    editingNameState: MutableState<Boolean>,
    confirmClearState: MutableState<Boolean>,
) {
    var nameDraft by nameDraftState
    var editingName by editingNameState
    var confirmClear by confirmClearState

    TokenDivider(first = false)
    TokenRow(
        title = "接收目录",
        subtitle = if (stats.isEmpty) {
            "暂无已接收文件"
        } else {
            "占用 " + TransferFormat.fileSize(stats.totalBytes) + " · 共 " + stats.count + " 个"
        },
        icon = Icons.Outlined.Folder,
        trailing = {
            AppButton(
                text = "清理",
                onClick = {
                    // 没有文件时不弹「删除 0 个文件」的空确认，直接给一句提示
                    if (stats.isEmpty) {
                        toast.show("暂无可清理的接收文件")
                    } else {
                        confirmClear = true
                    }
                },
                variant = AppButtonVariant.Ghost,
                tone = HapticTone.REJECT,
            )
        },
    )
    Spacer(Modifier.height(t.space1))

    ConfirmDialog(
        visible = editingName,
        title = "本机名称",
        message = "对端设备列表里显示的名字，最多 $ALIAS_MAX_LENGTH 个字符",
        confirmLabel = "保存",
        dismissLabel = "取消",
        onDismiss = { editingName = false },
        onConfirm = {
            val name = nameDraft.trim().take(ALIAS_MAX_LENGTH)
            if (name.isNotBlank()) {
                transferViewModel.setAlias(name)
                toast.show("本机名称已改为「" + name + "」")
            }
            editingName = false
        },
        extraContent = {
            BasicTextField(
                value = nameDraft,
                onValueChange = { if (it.length <= ALIAS_MAX_LENGTH) nameDraft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = t.fg),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.fgFade(6))
                    .padding(t.space3),
            )
        },
    )
    ConfirmDialog(
        visible = confirmClear,
        title = "清理接收目录",
        message = "将删除「Downloads/局域网互传/」下的 " + stats.count +
            " 个文件，并移除对应的接收记录。此操作不可撤销。",
        confirmLabel = "删除",
        dismissLabel = "取消",
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            scope.launch {
                val removed = withContext(Dispatchers.IO) {
                    transferViewModel.clearReceivedHistory()
                }
                toast.show("已清理 $removed 个文件")
            }
        },
    )
}
