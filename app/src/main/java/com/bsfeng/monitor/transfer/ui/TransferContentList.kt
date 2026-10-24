package com.bsfeng.monitor.transfer.ui

// 从 transfer/ui/TransferScreen.kt 拆出：传送页的滚动内容区
// （接收卡 → 附近设备 → 发送按钮 → 进行中 → 最近完成）。

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
internal fun TransferContentList(
    viewModel: TransferViewModel,
    t: com.bsfeng.monitor.ui.theme.DesignTokens,
    devices: List<com.bsfeng.monitor.domain.transfer.PeerDevice>,
    tasks: List<com.bsfeng.monitor.domain.transfer.TransferTask>,
    selected: String?,
    running: Boolean,
    onlineCount: Int,
    backgroundReceive: Boolean,
    windowRemainingMs: Long,
    autoCloseRemainingMs: Long,
    thresholdMb: Int,
    onPickFiles: () -> Unit,
) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = t.space4,
                    end = t.space4,
                    top = t.space2,
                    bottom = t.space12,
                ),
                verticalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                // 接收状态只留一张卡：服务开关（运行/停止）与「后台常驻」模式合并成一句话，
                // 避免「后台接收已开启」和「未开启接收」两行并排出现时自相矛盾。
                item {
                    ReceivingCard(
                        running = running,
                        port = viewModel.localPort,
                        onlineCount = onlineCount,
                        backgroundReceive = backgroundReceive,
                        windowRemainingMs = windowRemainingMs,
                        autoCloseRemainingMs = autoCloseRemainingMs,
                        thresholdMb = thresholdMb,
                        onStart = { viewModel.startReceiving() },
                        onStop = { viewModel.stopReceiving() },
                    )
                }
                item {
                    Text(
                        "附近设备",
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg2,
                        modifier = Modifier.padding(top = t.space2),
                    )
                }
                if (devices.isEmpty()) {
                    item {
                        EmptyState(
                            text = "同一 Wi-Fi 下打开 Mac 端「传送」即可互相发现",
                            title = "等待设备出现",
                            icon = Icons.Outlined.Devices,
                        )
                    }
                } else {
                    itemsIndexed(devices, key = { _, device -> device.deviceId }) { index, device ->
                        DeviceRow(
                            device = device,
                            selected = selected == device.deviceId,
                            paired = viewModel.pairedDeviceIds().contains(device.deviceId),
                            index = index,
                            onClick = { viewModel.selectDevice(device.deviceId) },
                            onRemovePairing = { viewModel.removePairing(device.deviceId) },
                        )
                    }
                }
                item {
                    Button(
                        onClick = { onPickFiles() },
                        enabled = selected != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = t.accent,
                            contentColor = t.accentOn,
                        ),
                    ) {
                        Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(t.space5))
                        Spacer(Modifier.width(t.space2))
                        Text(
                            if (selected == null) "先选择一台设备" else "选择文件发送",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val activeTasks = TransferTaskGroups.active(tasks)
                val finishedTasks = TransferTaskGroups.finished(tasks).take(MAX_FINISHED_TASKS)
                item {
                    Text(
                        TransferTaskGroups.activeTitle(tasks),
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg2,
                        modifier = Modifier.padding(top = t.space2),
                    )
                }
                if (activeTasks.isEmpty()) {
                    item { HintText("暂无进行中的传输") }
                } else {
                    itemsIndexed(
                        activeTasks,
                        key = { _, task -> task.sessionId + "#" + task.file.fileId },
                    ) { index, task ->
                        SwipeableTaskCard(
                            task = task,
                            index = index,
                            onCancel = { viewModel.cancelTask(task.sessionId) },
                            onDelete = { viewModel.deleteTask(task.sessionId, task.file.fileId) },
                        )
                    }
                }
                item {
                    Text(
                        "最近完成",
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg2,
                        modifier = Modifier.padding(top = t.space2),
                    )
                }
                if (finishedTasks.isEmpty()) {
                    item { HintText("完成的传输会留在这里，左右滑动可删除") }
                } else {
                    itemsIndexed(
                        finishedTasks,
                        key = { _, task -> task.sessionId + "#" + task.file.fileId },
                    ) { index, task ->
                        SwipeableTaskCard(
                            task = task,
                            index = index,
                            onCancel = { viewModel.cancelTask(task.sessionId) },
                            onDelete = { viewModel.deleteTask(task.sessionId, task.file.fileId) },
                        )
                    }
                }
            }
}
