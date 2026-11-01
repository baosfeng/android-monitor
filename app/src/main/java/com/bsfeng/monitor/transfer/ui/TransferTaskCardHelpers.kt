package com.bsfeng.monitor.transfer.ui

// 从 transfer/ui/TransferTaskCard.kt 拆出：任务卡片的辅助件
// （完成勾 / 失败抖动 / PIN 数字 / 倒计时 / 详情文案）。

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun CompletionCheck() {
    val t = LocalDesignTokens.current
    val motion = rememberMotionEnabled()
    val anim = remember { Animatable(if (motion) 0.4f else 1f) }
    LaunchedEffect(motion) {
        if (motion) {
            anim.animateTo(1f, t.springOf(MotionToken.OverlayIn))
        } else {
            anim.snapTo(1f)
        }
    }
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "传输完成",
        tint = t.success,
        modifier = Modifier
            .size(t.space5)
            .graphicsLayer {
                scaleX = anim.value
                scaleY = anim.value
            },
    )
}

/** 失败抖动：±4px × 3，时长取 `dur-page`、曲线 `ease-in`（协议 300ms 无独立 token）。 */
@Composable
internal fun rememberFailureShake(failed: Boolean): Animatable<Float, *> {
    val t = LocalDesignTokens.current
    val motion = rememberMotionEnabled()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(failed, motion) {
        if (!failed || !motion) {
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        val step = (t.durPage / 6).coerceAtLeast(1)
        repeat(3) {
            anim.animateTo(SHAKE_PX, tween(step, easing = t.easeIn))
            anim.animateTo(-SHAKE_PX, tween(step, easing = t.easeIn))
        }
        anim.animateTo(0f, tween(step, easing = t.easeIn))
    }
    return anim
}

/** PIN 每位填入：scale 0.9→1，`springOf(Press)`（120ms）。 */
@Composable
internal fun PinDigit(char: Char, index: Int) {
    val t = LocalDesignTokens.current
    val motion = rememberMotionEnabled()
    val scale = remember(index) { Animatable(if (motion) 0.9f else 1f) }
    LaunchedEffect(index, motion) {
        if (motion) scale.animateTo(1f, t.springOf(MotionToken.Press)) else scale.snapTo(1f)
    }
    Box(
        modifier = Modifier
            .size(t.space8)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.accentFade(12))
            .border(1.dp, t.accentFade(24), RoundedCornerShape(t.radiusMd)),
        contentAlignment = Alignment.Center,
    ) {
        Text(char.toString(), style = MaterialTheme.typography.titleLarge, color = t.accent)
    }
}

/** 接收端 PIN 剩余秒数（向上取整，每秒刷新；deadline 为空时返回 null）。 */
@Composable
internal fun rememberSecondsUntil(deadlineMs: Long?): Int? {
    if (deadlineMs == null) return null
    var remaining by remember(deadlineMs) { mutableStateOf(secondsUntil(deadlineMs)) }
    LaunchedEffect(deadlineMs) {
        while (true) {
            remaining = secondsUntil(deadlineMs)
            if (remaining <= 0) break
            delay(1_000L)
        }
    }
    return remaining
}

internal fun secondsUntil(deadlineMs: Long): Int {
    val left = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
    return ((left + 999L) / 1_000L).toInt()
}

/**
 * 本地时区 `HH:mm`（口径文案里的「14:32」）。
 *
 * @param zone 单测传固定时区，避免用例随宿主时区漂移。
 */
internal fun formatClock(atMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (atMs <= 0L) return "--:--"
    val format = SimpleDateFormat("HH:mm", Locale.US)
    format.timeZone = zone
    return format.format(Date(atMs))
}

/** 任务行副文案：进行中显示「已传 / 总量 · 速度」，已完成只显示总体积，失败/取消只保留已传/总量。 */
internal fun taskDetailText(task: TransferTask): String = buildString {
    append(if (task.direction == TransferDirection.SEND) "发送" else "接收")
    append(" · ")
    append(statusLabel(task))
    append(" · ")
    if (task.status == TransferStatus.COMPLETED) {
        append(TransferFormat.fileSize(task.file.size))
    } else {
        append(TransferFormat.progressPair(task.receivedBytes, task.file.size))
        // 速度是过程信息：只有进行中才显示（终态卡片不再挂动态数值）
        if (task.isActive) {
            val speed = TransferFormat.speed(task.bytesPerSecond)
            if (speed.isNotBlank()) {
                append(" · ")
                append(speed)
            }
        }
    }
}

/**
 * 顶部「接收状态」区（单一真值源 = [TransferEngine.running]）。
 *
 * - 关闭：一行「未开启接收 · 仅应用内」+ 说明 + 一键开启；
 * - 开启：「接收已开启 · 端口 / 在线数 · 后台常驻（或仅应用内）」+ 关闭按钮；
 * - 开了「自动关闭接收」时多一行剩余时间（最后 60 秒转警示色），到点由引擎停服务、磁贴回 INACTIVE；
 * - 「后台常驻」是模式标签，附在状态行末尾，不再单独占一行（否则与「未开启接收」并排自相矛盾）；
 * - 磁贴、设置页、本卡片都读写同一状态，不再各存一份。
 */
