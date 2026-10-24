package com.bsfeng.monitor.transfer.ui

// 从 transfer/ui/TransferScreen.kt 拆出：接收侧卡片（端口 / 可被发现 / 暂停接收）与状态文案。

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.service.ReceivingState
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.opaquePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun ReceivingCard(
    running: Boolean,
    port: Int,
    onlineCount: Int,
    backgroundReceive: Boolean,
    windowRemainingMs: Long,
    autoCloseRemainingMs: Long,
    thresholdMb: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val modeLabel = if (backgroundReceive) "后台常驻" else "仅应用内"
    val motionEnabled = rememberMotionEnabled()
    // 「自动关闭」文案：最后 60 秒切警示色（颜色过渡取 dur-state，reduced-motion 时直接落终态）
    val autoCloseText = if (running && autoCloseRemainingMs > 0L) {
        ReceivingAutoClose.remainingText(autoCloseRemainingMs)
    } else {
        null
    }
    val autoCloseColor by animateColorAsState(
        targetValue = if (autoCloseText != null && ReceivingAutoClose.isWarning(autoCloseRemainingMs)) {
            t.warnText
        } else {
            t.muted
        },
        animationSpec = if (motionEnabled) tween(t.durState) else snap(),
        label = "autoCloseColor",
    )
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "接收已开启" else "未开启接收",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                )
                Spacer(Modifier.height(t.space1))
                Text(
                    if (running) {
                        ReceivingState.of(true, port, onlineCount).statusText + " · " + modeLabel
                    } else {
                        // 关闭态只说明「开启后会发生什么」；不把「后台常驻」贴在「未开启接收」旁边
                        // （那是开启后的模式，不是当前状态，并排会读成自相矛盾）。
                        "同一 Wi-Fi 下开启后，对端就能发现本机 · " +
                            if (backgroundReceive) "开启后常驻后台" else "仅应用内"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            if (running) {
                Text(
                    "关闭",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.accent,
                    modifier = Modifier
                        .padding(start = t.space2)
                        .clip(RoundedCornerShape(t.radiusSm))
                        .clickable(onClick = onStop)
                        .padding(horizontal = t.space2, vertical = t.space1),
                )
            }
        }
        if (!running) {
            Spacer(Modifier.height(t.space3))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = t.accent,
                    contentColor = t.accentOn,
                ),
            ) {
                Text("开启接收", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (autoCloseText != null) {
            Spacer(Modifier.height(t.space2))
            Text(
                autoCloseText,
                style = MaterialTheme.typography.bodySmall,
                color = autoCloseColor,
            )
        }
        if (running && windowRemainingMs > 0L) {
            Spacer(Modifier.height(t.space2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    text = "免配对 " + TransferFormat.windowRemaining(windowRemainingMs),
                    tone = BadgeTone.Warn,
                )
                Spacer(Modifier.width(t.space2))
                Text(
                    "未配对设备在窗口内免 PIN",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        if (running && windowRemainingMs <= 0L && thresholdMb > 0) {
            Spacer(Modifier.height(t.space2))
            Text(
                "小于 " + TransferFormat.threshold(thresholdMb) + " 的文件免配对",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}

/** 空分组提示（比 EmptyState 轻，避免列表里出现两个大空态）。 */
@Composable
internal fun HintText(text: String) {
    val t = LocalDesignTokens.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = t.muted,
        modifier = Modifier.padding(vertical = t.space2),
    )
}

internal fun statusLabel(task: TransferTask): String = when (task.status) {
    TransferStatus.IDLE -> "准备中"
    TransferStatus.OFFERED -> "已提交清单"
    TransferStatus.AWAITING_CONFIRM -> "等待 PIN 确认"
    TransferStatus.TRANSFERRING -> "传输中"
    TransferStatus.COMPLETED -> "已完成"
    TransferStatus.FAILED -> task.errorCode?.let { "失败（$it）" } ?: "失败"
    TransferStatus.CANCELLED -> "已取消"
    TransferStatus.DENIED -> "对方拒绝"
}

/** 抖动幅度（协议 §7：±4px）。 */
internal const val SHAKE_PX = 4f

/** 滑动到什么比例时背景红底达到完全不透明（更早看见「红底 + 垃圾桶」）。 */
internal const val REVEAL_FULL_PROGRESS = 0.35f

/** 「最近完成」最多展示条数（更早的记录由删除整理，不做无限增长）。 */
internal const val MAX_FINISHED_TASKS = 20
