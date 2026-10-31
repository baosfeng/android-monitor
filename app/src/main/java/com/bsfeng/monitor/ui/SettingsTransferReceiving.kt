package com.bsfeng.monitor.ui

// 二级页「传送」的接收侧三件事：本机名称（改名）/ 后台接收 / 自动关闭接收 / 通知栏磁贴，
// 以及接收目录（占用、清理）。纯「收到文件之后怎么落地」的一组，与「提醒与免配对」分开一页。
//
// 接收目录行（ui/SettingsTransferStorageRow.kt）此前是零调用点的死代码 —— 功能本身完整
// （占用展示 / 改名 / 清理二次确认），本次接回产品，不再另写一份。

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 传送接收侧的行（放在调用方的 AppCard 里）。
 *
 * 只在功能开启且 ViewModel 已创建时渲染：关闭时连 Flow 都不订阅，更不会去拉起引擎。
 */
@Composable
internal fun SettingsTransferReceivingRows(
    t: DesignTokens,
    transferViewModel: TransferViewModel,
    toast: ToastHostState,
    context: Context,
    scope: CoroutineScope,
    view: View,
) {
    val stats by transferViewModel.receivedStats.collectAsState()
    val backgroundReceive by transferViewModel.backgroundReceive.collectAsState()
    val autoCloseMinutes by transferViewModel.autoCloseMinutes.collectAsState()
    val myAlias by transferViewModel.aliasFlow.collectAsState()
    val editingNameState = remember { mutableStateOf(false) }
    val nameDraftState = remember { mutableStateOf("") }
    val confirmClearState = remember { mutableStateOf(false) }
    var editingName by editingNameState
    var nameDraft by nameDraftState

    // 进本页即刷新接收目录占用（MediaStore 查询放 IO）
    LifecycleResumeEffect(Unit) {
        scope.launch { withContext(Dispatchers.IO) { transferViewModel.refreshReceivedStats() } }
        onPauseOrDispose { }
    }

    val requestTile = rememberRequestTransferTile(context, toast)

    TokenRow(
        title = "本机名称",
        subtitle = myAlias + " · 对端设备列表里显示的名字",
        icon = Icons.Outlined.Smartphone,
        onClick = {
            nameDraft = myAlias
            editingName = true
        },
        trailing = { TailNum(myAlias); TailChevron() },
    )
    TokenDivider(first = false)
    TokenRow(
        title = "后台接收",
        subtitle = if (backgroundReceive) {
            "常驻前台服务 · 通知显示在线设备"
        } else {
            "仅 App 可见时运行 · 默认关闭"
        },
        icon = Icons.Outlined.NotificationsActive,
        trailing = {
            AppSwitch(backgroundReceive, { enabled ->
                transferViewModel.setBackgroundReceive(enabled)
                view.performConfirmHaptic()
                toast.show(
                    if (enabled) "已开启后台接收（常驻通知）" else "已关闭后台接收（仅 App 内可见）"
                )
            }, contentDescription = "后台接收")
        },
    )
    TokenDivider(first = false)
    // 「自动关闭接收」：到点自动停服务，避免不用时白白耗电。
    // 只持久化时长，不持久化计时起点（进程重启后重新计时）。
    TokenRow(
        title = "自动关闭接收",
        subtitle = if (autoCloseMinutes == ReceivingAutoClose.OFF) {
            "关闭：一直保持接收，直到手动关闭（默认）"
        } else {
            "开启接收后 " + ReceivingAutoClose.label(autoCloseMinutes) + "自动停止 · 省电"
        },
        icon = Icons.Outlined.Timer,
        trailing = {
            TailNum(
                if (autoCloseMinutes == ReceivingAutoClose.OFF) "关"
                else ReceivingAutoClose.label(autoCloseMinutes)
            )
        },
    )
    ChipRow(
        items = ReceivingAutoClose.MINUTES_OPTIONS.map {
            ReceivingAutoClose.label(it)
        },
        selectedIndex = ReceivingAutoClose.MINUTES_OPTIONS
            .indexOf(autoCloseMinutes).coerceAtLeast(0),
        onSelect = { index ->
            val minutes = ReceivingAutoClose.MINUTES_OPTIONS[index]
            transferViewModel.setAutoCloseMinutes(minutes)
            view.performConfirmHaptic()
            toast.show(
                if (minutes == ReceivingAutoClose.OFF) {
                    "已关闭「自动关闭接收」"
                } else {
                    "接收将在 " + ReceivingAutoClose.label(minutes) + "后自动关闭"
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
        title = "通知栏磁贴",
        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "一键添加到快捷设置，下拉即开关"
        } else {
            "下拉通知栏 → 编辑 → 添加「局域网互传」"
        },
        icon = Icons.Outlined.Widgets,
        trailing = {
            AppButton(
                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "添加" else "说明",
                onClick = { requestTile() },
                variant = AppButtonVariant.Ghost,
            )
        },
    )
    // 接收目录：占用 / 文件数 / 清理入口 + 改名与清理两个确认弹窗
    SettingsTransferStorageRow(
        t = t,
        toast = toast,
        scope = scope,
        view = view,
        transferViewModel = transferViewModel,
        stats = stats,
        nameDraftState = nameDraftState,
        editingNameState = editingNameState,
        confirmClearState = confirmClearState,
    )
    Spacer(Modifier.height(t.space1))
}
