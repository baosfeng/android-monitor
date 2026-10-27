package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置「传送」功能卡内的专属设置（本机名称 / 后台接收 / 自动关闭接收 /
// 通知栏磁贴 / 三种提示音 / 免配对窗口 / 体积阈值 / 接收目录与清理）。负责「传送在设置里怎么配」。

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「传送」功能卡内的专属设置（后台接收 / 提示音 / 免配对窗口 / 体积阈值 / 接收目录）。
 *
 * 只在功能开启且 ViewModel 已创建时渲染；关闭时整块折叠成一句「开启后可用」，
 * 因此传送关闭时这里连 Flow 都不订阅，更不会去拉起引擎。
 */
@Composable
internal fun TransferFeatureSettings(
    transferViewModel: TransferViewModel,
    toast: ToastHostState,
    context: Context,
    scope: CoroutineScope,
    view: View,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        val sound by transferViewModel.soundSettings.collectAsState()
        val windowState by transferViewModel.openPairing.collectAsState()
        val thresholdMb by transferViewModel.autoApproveThresholdMb.collectAsState()
        val stats by transferViewModel.receivedStats.collectAsState()
        val confirmClearState = remember { mutableStateOf(false) }
        var confirmClear by confirmClearState
        var windowLeftMs by remember { mutableStateOf(windowState.remainingMs()) }
        val editingNameState = remember { mutableStateOf(false) }
        var editingName by editingNameState
        val nameDraftState = remember { mutableStateOf("") }
        var nameDraft by nameDraftState

        LaunchedEffect(windowState) {
            while (true) {
                windowLeftMs = windowState.remainingMs()
                if (windowLeftMs <= 0L) break
                delay(1_000L)
            }
        }
        // 进设置页即刷新接收目录占用（MediaStore 查询放 IO）
        LifecycleResumeEffect(Unit) {
            scope.launch { withContext(Dispatchers.IO) { transferViewModel.refreshReceivedStats() } }
            onPauseOrDispose { }
        }

        val requestTile = rememberRequestTransferTile(context, toast)

        val backgroundReceive by transferViewModel.backgroundReceive.collectAsState()
        val autoCloseMinutes by transferViewModel.autoCloseMinutes.collectAsState()
        val myAlias by transferViewModel.aliasFlow.collectAsState()

        TokenRow(
            title = "本机名称",
            subtitle = myAlias + " · 对端设备列表里显示的名字",
            icon = Icons.Outlined.Smartphone,
            onClick = {
                nameDraft = myAlias
                editingName = true
            },
            trailing = { TailChevron() },
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
                })
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
                    onClick = {
                        requestTile()
                    },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
        TokenDivider(first = false)
        SettingsTransferPairingRows(
            t = t,
            view = view,
            toast = toast,
            transferViewModel = transferViewModel,
            sound = sound,
            windowState = windowState,
            windowLeftMs = windowLeftMs,
            thresholdMb = thresholdMb,
        )
        Spacer(Modifier.height(t.space1))
    }
}
