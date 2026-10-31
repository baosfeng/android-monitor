package com.bsfeng.monitor.ui

// 二级页「传送 · 提醒与配对」：三种提示音 / 免配对码窗口 / 小于 N MB 免配对。
// 负责「收到文件时怎么提醒」与「哪些传输可以免 PIN」。
//
// 与「传送 · 接收」分开一页：接收回答「文件落在哪、服务跑不跑」（SettingsTransferReceiving.kt），
// 提醒与配对回答「会不会响、要不要输 PIN」。两页各 5 项，合起来 10 项会超过单页上限。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import android.view.View
import kotlinx.coroutines.delay

/**
 * 提示音 + 免配对窗口 + 体积阈值三组设置（放在调用方的 AppCard 里）。
 *
 * 调用方保证 [transferViewModel] 非 null（功能关闭时不建 VM、也不渲染本组件）。
 */
@Composable
internal fun SettingsTransferPairingRows(
    t: DesignTokens,
    view: View,
    toast: ToastHostState,
    transferViewModel: TransferViewModel,
) {
    val sound by transferViewModel.soundSettings.collectAsState()
    val windowState by transferViewModel.openPairing.collectAsState()
    val thresholdMb by transferViewModel.autoApproveThresholdMb.collectAsState()
    var windowLeftMs by remember { mutableStateOf(windowState.remainingMs()) }

    LaunchedEffect(windowState) {
        while (true) {
            windowLeftMs = windowState.remainingMs()
            if (windowLeftMs <= 0L) break
            delay(1_000L)
        }
    }

    TokenRow(
        title = "传输完成提示",
        subtitle = "系统通知音 + 震动",
        icon = Icons.Outlined.VolumeUp,
        trailing = {
            AppSwitch(sound.complete, { enabled ->
                transferViewModel.setSound(enabled, TransferViewModel.SoundKind.COMPLETE)
                view.performConfirmHaptic()
                toast.show(if (enabled) "已开启传输完成提示" else "已关闭传输完成提示")
            })
        },
    )
    TokenDivider(first = false)
    TokenRow(
        title = "传输失败提示",
        subtitle = "提示音 + 震动（默认关）",
        icon = Icons.Outlined.VolumeOff,
        trailing = {
            AppSwitch(sound.failure, { enabled ->
                transferViewModel.setSound(enabled, TransferViewModel.SoundKind.FAILURE)
                view.performConfirmHaptic()
            })
        },
    )
    TokenDivider(first = false)
    TokenRow(
        title = "新设备出现提示",
        subtitle = "仅震动（默认关）",
        icon = Icons.Outlined.Vibration,
        trailing = {
            AppSwitch(sound.newDevice, { enabled ->
                transferViewModel.setSound(enabled, TransferViewModel.SoundKind.NEW_DEVICE)
                view.performConfirmHaptic()
            })
        },
    )
    TokenDivider(first = false)
    TokenRow(
        title = "免配对码窗口",
        subtitle = if (windowState.enabled) {
            "剩余 " + TransferFormat.windowRemaining(windowLeftMs) + " · 到期自动回到需配对"
        } else {
            "未开启：未配对设备需输入 4 位 PIN"
        },
        subtitleColor = if (windowLeftMs > 0L) t.successText else t.muted,
        icon = Icons.Outlined.LockOpen,
        trailing = {
            TailNum(if (windowState.enabled) windowState.minutes.toString() + " 分钟" else "关")
            AppSwitch(windowState.enabled, { enabled ->
                transferViewModel.setOpenPairingWindow(enabled)
                view.performConfirmHaptic()
                toast.show(
                    if (enabled) {
                        "免配对窗口已开启：" + windowState.minutes + " 分钟内免 PIN"
                    } else {
                        "已关闭免配对窗口"
                    }
                )
            })
        },
    )
    // 窗口时长：开启时切换会以新时长重新计时；
    // 未开启时整组降透明度，避免「未开启」与高亮的「10 分钟」并排读成自相矛盾
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (windowState.enabled) 1f else 0.55f }
            .padding(horizontal = t.space4, vertical = t.space2),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        PairingPolicy.WINDOW_MINUTES.forEach { minutes ->
            Chip(
                label = minutes.toString() + " 分钟",
                selected = windowState.minutes == minutes,
                onClick = {
                    transferViewModel.setOpenPairingMinutes(minutes)
                    view.performConfirmHaptic()
                },
            )
        }
    }
    TokenDivider(first = false)
    TokenRow(
        title = "小于 N MB 免配对码",
        subtitle = if (thresholdMb > 0) {
            "≤ " + TransferFormat.threshold(thresholdMb) + " 的文件直接接收"
        } else {
            "关闭：一律需要 PIN（默认）"
        },
        icon = Icons.Outlined.DataUsage,
        trailing = {
            TailNum(if (thresholdMb > 0) TransferFormat.threshold(thresholdMb) else "关")
        },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = t.space4, vertical = t.space2),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        PairingPolicy.THRESHOLD_OPTIONS_MB.forEach { mb ->
            Chip(
                label = TransferFormat.threshold(mb),
                selected = thresholdMb == mb,
                onClick = {
                    transferViewModel.setAutoApproveThresholdMb(mb)
                    view.performConfirmHaptic()
                    toast.show(
                        if (mb == 0) "已关闭体积阈值" else "≤ ${TransferFormat.threshold(mb)} 的文件免配对"
                    )
                },
            )
        }
    }
}
