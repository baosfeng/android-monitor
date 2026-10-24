package com.bsfeng.monitor.transfer.ui

// 从 transfer/ui/TransferScreen.kt 拆出：任务卡片本体与它的辅助（完成勾、失败抖动、PIN 数字、倒计时、详情文案）。

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
internal fun TaskCard(
    task: TransferTask,
    index: Int,
    onCancel: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val failed = task.status == TransferStatus.FAILED || task.status == TransferStatus.DENIED
    val shake = rememberFailureShake(failed)
    val fraction by animateFloatAsState(
        targetValue = if (task.status == TransferStatus.COMPLETED) 1f else task.fraction,
        animationSpec = tween(t.durState, easing = t.easeStandard),
        label = "taskFraction",
    )
    val accent = when (task.status) {
        TransferStatus.COMPLETED -> t.success
        TransferStatus.FAILED, TransferStatus.DENIED -> t.danger
        TransferStatus.CANCELLED -> t.muted
        else -> t.accent
    }
    AppCard(
        modifier = Modifier
            .staggerEnterAt(index)
            .graphicsLayer { translationX = shake.value },
        elevation = if (task.isActive) 1 else 0,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    // 超长文件名最多两行，其余省略：卡片高度不随文件名爆炸
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(t.space1))
                Text(
                    taskDetailText(task),
                    style = MaterialTheme.typography.bodySmall,
                    // 失败态副文案直接走 danger，和「已完成」的灰色一眼分开
                    color = if (failed) t.danger else t.muted,
                )
            }
            if (task.isActive) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(t.radiusSm))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = t.space2, vertical = t.space1),
                )
            } else {
                when (task.status) {
                    TransferStatus.COMPLETED -> CompletionCheck()
                    TransferStatus.FAILED, TransferStatus.DENIED -> Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = "传输失败",
                        tint = t.danger,
                        modifier = Modifier.size(t.space5),
                    )
                    TransferStatus.CANCELLED -> Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = "已取消",
                        tint = t.muted,
                        modifier = Modifier.size(t.space5),
                    )
                    else -> Unit
                }
            }
        }
        // 进度条只属于「进行中」：终态卡片只保留文件名 + 方向/大小 + 状态图标，
        // 不再挂一条满格绿条（那是过程信息，完成/失败/取消后没有意义）。
        if (task.isActive) {
            Spacer(Modifier.height(t.space3))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(t.space1)
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(t.fgFade(10)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(t.radiusPill))
                        .background(accent),
                )
            }
        }
        if (failed && task.message.isNotBlank()) {
            Spacer(Modifier.height(t.space2))
            Text(
                task.message,
                style = MaterialTheme.typography.bodySmall,
                color = t.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 完成对勾：`springOf(OverlayIn)`（220ms，--ease-spring 过冲曲线）。 */
