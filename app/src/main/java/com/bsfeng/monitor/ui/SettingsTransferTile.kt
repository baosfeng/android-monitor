package com.bsfeng.monitor.ui

// 从 ui/SettingsTransferCard.kt 拆出：把「局域网互传」加进快捷设置磁贴（API 33+ 一键请求，低版本给文案引导）。
// 系统不允许静默添加，因此只有 API 33+ 会真正发起请求，其余版本只提示手动路径。

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.runtime.Composable
import com.bsfeng.monitor.R
import com.bsfeng.monitor.transfer.service.TransferTileService
import com.bsfeng.monitor.transfer.service.TransferTileState
import com.bsfeng.monitor.ui.components.ToastHostState

/**
 * 请求把互传磁贴加进快捷设置。
 *
 * API 33 以下系统没有 requestAddTileService，只能给一句手动路径的提示。
 * 返回的函数可直接作为按钮 onClick 使用。
 */
@Composable
internal fun rememberRequestTransferTile(
    context: Context,
    toast: ToastHostState,
): () -> Unit {
    return {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast.show("下拉通知栏 → 编辑 → 添加「局域网互传」")
        } else {
            runCatching {
                val sbm = context.getSystemService(StatusBarManager::class.java)
                sbm?.requestAddTileService(
                    ComponentName(context, TransferTileService::class.java),
                    TransferTileState.LABEL,
                    Icon.createWithResource(context, R.drawable.ic_transfer_tile),
                    context.mainExecutor,
                ) { result ->
                    toast.show(
                        when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                "已添加到快捷设置"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                "磁贴已在快捷设置里"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                                "已取消添加"
                            else -> "添加失败（$result），可手动从「编辑」里添加"
                        },
                    )
                }
            }.onFailure { toast.show("添加失败，请到通知栏「编辑」里手动添加") }
        }
    }
}
