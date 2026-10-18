package com.bsfeng.monitor.transfer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.transfer.service.ServiceModePolicy
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.opaquePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「传送」Tab（协议 §8 / §9 的落点）。
 *
 * 四段结构：**可被发现**（发现开关 + 免配对窗口倒计时）→ **附近设备**（点选发送目标）→
 * **进行中**（进度 / 速度 / 取消）→ **最近完成**（体积 / 已终结任务可左右滑动删除 + 5s 撤销）。
 *
 * 提示音、免配对窗口时长、体积阈值、接收目录清理都收敛到「设置 → 局域网互传」，本页不再放设置项。
 *
 * 动效全部取 `DesignTokenTable` 的 `dur-*` / `ease-*`（含 reduced-motion 兜底）：
 * 设备与任务入场 = `rememberStaggerEnter`（`dur-page` + `ease-out` + 逐项 `stagger`），
 * 进度推进 = `dur-state`（120ms），完成对勾 = `springOf(OverlayIn)`（220ms 过冲曲线），
 * 失败抖动 = `dur-page` + `ease-in`（协议 300ms 无独立 token，取最近一档）。
 */
@Composable
fun TransferScreen(
    modifier: Modifier = Modifier,
    viewModel: TransferViewModel = viewModel(),
) {
    val t = LocalDesignTokens.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val pin by viewModel.pinPrompt.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val selected by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val discoverable by viewModel.discoverable.collectAsStateWithLifecycle()
    val openPairing by viewModel.openPairing.collectAsStateWithLifecycle()
    val thresholdMb by viewModel.autoApproveThresholdMb.collectAsStateWithLifecycle()
    val backgroundReceive by viewModel.backgroundReceive.collectAsStateWithLifecycle()
    val view = LocalView.current
    var pinInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.ensureStarted() }
    DisposableEffect(view) {
        viewModel.setHapticTarget(view)
        onDispose { viewModel.setHapticTarget(null) }
    }
    LaunchedEffect(pin?.sessionId) { pinInput = "" }
    // 免配对窗口剩余时间每秒刷新（到期后文案自行消失）
    var windowRemainingMs by remember { mutableStateOf(openPairing.remainingMs()) }
    LaunchedEffect(openPairing) {
        while (true) {
            windowRemainingMs = openPairing.remainingMs()
            if (windowRemainingMs <= 0L) break
            delay(1_000L)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.deletedTasks.collect { deleted ->
            // 5s 撤销窗口：窗口内点「撤销」恢复记录；到期或手动关掉则提交删除
            val result = withTimeoutOrNull(viewModel.undoWindowMs) {
                snackbarHostState.showSnackbar(
                    message = "已删除",
                    actionLabel = "撤销",
                    withDismissAction = false,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) {
                val restored = viewModel.undoDelete(deleted.deletionId)
                if (restored?.fileDeleted == true) {
                    snackbarHostState.showSnackbar(
                        message = "文件已删除，仅恢复记录",
                        duration = SnackbarDuration.Short,
                    )
                }
            } else {
                viewModel.commitDelete(deleted.deletionId)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.sendFiles(uris)
    }

    val onlineCount = devices.count { it.online }
    val meta = buildString {
        append(if (running) "端口 ${viewModel.localPort}" else "服务未就绪")
        append(" · 在线 $onlineCount 台")
        append(" · ${viewModel.alias}")
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(title = "局域网互传", meta = meta)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = t.space4, end = t.space4, bottom = t.space12),
                verticalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                item {
                    HintText(
                        ServiceModePolicy.modeHint(backgroundReceive) +
                            " · 通知栏磁贴可一键开关",
                    )
                }
                item { DiscoverableCard(
                    discoverable = discoverable,
                    running = running,
                    port = viewModel.localPort,
                    onlineCount = onlineCount,
                    windowRemainingMs = windowRemainingMs,
                    thresholdMb = thresholdMb,
                    onToggle = { viewModel.setDiscoverable(it) },
                ) }
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
                        onClick = { picker.launch("*/*") },
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
                val activeTasks = tasks.filter { it.isActive }
                val finishedTasks = tasks.filter { !it.isActive }.take(MAX_FINISHED_TASKS)
                item {
                    Text(
                        "进行中" + if (activeTasks.isEmpty()) "" else " · " + activeTasks.size,
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg2,
                        modifier = Modifier.padding(top = t.space2),
                    )
                }
                if (activeTasks.isEmpty()) {
                    item { HintText("当前没有进行中的传输") }
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = t.space4, vertical = t.space3),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = t.opaquePanel,
                contentColor = t.fg,
                actionContentColor = t.accent,
                shape = RoundedCornerShape(t.radiusLg),
                modifier = Modifier.tokenShadow(2, RoundedCornerShape(t.radiusLg)),
            )
        }
    }

    val prompt = pin
    if (prompt != null && prompt.direction == TransferDirection.RECV) {
        val peerAlias = prompt.peerAlias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
        ConfirmDialog(
            visible = true,
            title = "「$peerAlias」请求发送",
            message = "${prompt.fileName} · 在对方设备上输入下方 PIN 即可开始",
            confirmLabel = "知道了",
            dismissLabel = "拒绝",
            onConfirm = viewModel::dismissPin,
            onDismiss = { viewModel.denyIncoming(prompt.sessionId) },
            extraContent = {
                val remaining = rememberSecondsUntil(prompt.expiresAtMs)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(t.space2, Alignment.CenterHorizontally),
                    ) {
                        prompt.pin?.forEachIndexed { index, char -> PinDigit(char, index) }
                    }
                    if (remaining != null) {
                        Spacer(Modifier.height(t.space2))
                        Text(
                            "${remaining}s 后自动拒绝",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (remaining <= 10) t.danger else t.muted,
                        )
                    }
                }
            },
        )
    } else if (prompt != null) {
        val peerAlias = prompt.peerAlias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
        val message = prompt.message.ifBlank { "请输入「$peerAlias」上显示的 4 位 PIN" }
        ConfirmDialog(
            visible = true,
            title = "配对确认",
            message = message,
            confirmLabel = "确定",
            dismissLabel = "取消",
            onConfirm = { viewModel.submitPin(pinInput) },
            onDismiss = viewModel::dismissPin,
            extraContent = {
                BasicTextField(
                    value = pinInput,
                    onValueChange = { text -> pinInput = text.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        color = t.accent,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(t.radiusMd))
                        .background(t.fgFade(6))
                        .padding(t.space3),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (pinInput.isEmpty()) {
                                Text(
                                    "····",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = t.muted,
                                )
                            }
                            inner()
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: PeerDevice,
    selected: Boolean,
    paired: Boolean,
    index: Int,
    onClick: () -> Unit,
    onRemovePairing: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val alias = device.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rememberStaggerEnter(index))
            .tokenShadow(if (selected) 1 else 0, shape)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, if (selected) t.accent else t.borderSoft, shape)
            .clickable(onClick = onClick)
            .padding(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(t.iconSize)
                .clip(CircleShape)
                .background(if (device.online) t.accentFade(14) else t.fgFade(8)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (device.deviceType == DeviceTypes.MAC) {
                    Icons.Outlined.Computer
                } else {
                    Icons.Outlined.Smartphone
                },
                contentDescription = alias,
                tint = if (device.online) t.accent else t.muted,
                modifier = Modifier.size(t.space6),
            )
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alias, style = MaterialTheme.typography.titleSmall, color = t.fg)
                if (paired) {
                    Spacer(Modifier.width(t.space2))
                    Badge("已配对", BadgeTone.Accent)
                }
            }
            Spacer(Modifier.height(t.space1))
            Text(
                buildString {
                    append(if (device.online) "在线" else "离线")
                    append(" · ${device.hostAddress.ifBlank { "未知地址" }}:${device.port}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        if (paired) {
            Text(
                "移除配对",
                style = MaterialTheme.typography.labelMedium,
                color = t.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusSm))
                    .clickable(onClick = onRemovePairing)
                    .padding(horizontal = t.space2, vertical = t.space1),
            )
        }
        if (selected) {
            Spacer(Modifier.width(t.space1))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = t.accent,
                modifier = Modifier.size(t.space5),
            )
        }
    }
}

/**
 * 可滑动删除的任务卡。
 *
 * 只对**已终结**任务生效：左滑 / 右滑都触发删除；进行中任务滑出后 `confirmValueChange` 返回 false，
 * 由 Material3 回弹到原位。背景统一 `--danger` + 垃圾桶图标。
 */
@Composable
private fun SwipeableTaskCard(
    task: TransferTask,
    index: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val deletable = task.status.isTerminal
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when {
                value == SwipeToDismissBoxValue.Settled -> true
                !deletable -> false
                else -> {
                    onDelete()
                    true
                }
            }
        },
    )
    // 撤销恢复的列表项可能复用同一 key 的保存态：从已滑出位置复位，否则卡片会停在屏外
    LaunchedEffect(Unit) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismissState.reset()
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val alignment = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(t.danger)
                    .padding(horizontal = t.space4),
                contentAlignment = alignment,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = t.accentOn,
                    modifier = Modifier.size(t.space5),
                )
            }
        },
    ) {
        TaskCard(
            task = task,
            index = index,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun TaskCard(
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
            .then(rememberStaggerEnter(index))
            .graphicsLayer { translationX = shake.value },
        elevation = if (task.isActive) 1 else 0,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                )
                Spacer(Modifier.height(t.space1))
                Text(
                    taskDetailText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
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
            } else if (task.status == TransferStatus.COMPLETED) {
                CompletionCheck()
            }
        }
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
        if (failed && task.message.isNotBlank()) {
            Spacer(Modifier.height(t.space2))
            Text(task.message, style = MaterialTheme.typography.bodySmall, color = t.danger)
        }
    }
}

/** 完成对勾：`springOf(OverlayIn)`（220ms，--ease-spring 过冲曲线）。 */
@Composable
private fun CompletionCheck() {
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
private fun rememberFailureShake(failed: Boolean): Animatable<Float, *> {
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
private fun PinDigit(char: Char, index: Int) {
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
private fun rememberSecondsUntil(deadlineMs: Long?): Int? {
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

private fun secondsUntil(deadlineMs: Long): Int {
    val left = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
    return ((left + 999L) / 1_000L).toInt()
}

/** 任务行副文案：进行中显示「已传 / 总量 · 速度」，已完成只显示总体积（需求 4a）。 */
private fun taskDetailText(task: TransferTask): String = buildString {
    append(if (task.direction == TransferDirection.SEND) "发送" else "接收")
    append(" · ")
    append(statusLabel(task))
    append(" · ")
    if (task.status == TransferStatus.COMPLETED) {
        append(TransferFormat.fileSize(task.file.size))
    } else {
        append(TransferFormat.progressPair(task.receivedBytes, task.file.size))
        val speed = TransferFormat.speed(task.bytesPerSecond)
        if (speed.isNotBlank()) {
            append(" · ")
            append(speed)
        }
    }
}

/** 「可被发现」卡片：发现开关 + 端口 / 在线数 + 免配对窗口剩余时间。 */
@Composable
private fun DiscoverableCard(
    discoverable: Boolean,
    running: Boolean,
    port: Int,
    onlineCount: Int,
    windowRemainingMs: Long,
    thresholdMb: Int,
    onToggle: (Boolean) -> Unit,
) {
    val t = LocalDesignTokens.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("可被发现", style = MaterialTheme.typography.titleSmall, color = t.fg)
                    Spacer(Modifier.width(t.space2))
                    Badge(
                        text = if (discoverable) "发现中" else "已关闭",
                        tone = if (discoverable) BadgeTone.Success else BadgeTone.Neutral,
                    )
                }
                Spacer(Modifier.height(t.space1))
                Text(
                    when {
                        !running -> "服务未就绪"
                        !discoverable -> "停止公告，对端看不到本机"
                        else -> "端口 $port · 在线 $onlineCount 台"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            AppSwitch(checked = discoverable, onCheckedChange = onToggle)
        }
        if (windowRemainingMs > 0L) {
            Spacer(Modifier.height(t.space2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(text = "免配对 " + TransferFormat.windowRemaining(windowRemainingMs), tone = BadgeTone.Warn)
                Spacer(Modifier.width(t.space2))
                Text(
                    "未配对设备在窗口内免 PIN",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        } else if (thresholdMb > 0) {
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
private fun HintText(text: String) {
    val t = LocalDesignTokens.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = t.muted,
        modifier = Modifier.padding(vertical = t.space2),
    )
}

private fun statusLabel(task: TransferTask): String = when (task.status) {
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
private const val SHAKE_PX = 4f

/** 「最近完成」最多展示条数（更早的记录由删除整理，不做无限增长）。 */
private const val MAX_FINISHED_TASKS = 20
