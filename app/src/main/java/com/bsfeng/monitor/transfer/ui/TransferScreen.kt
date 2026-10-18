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
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.bsfeng.monitor.transfer.ui.TransferViewModel.SoundKind
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
import java.util.Locale

/**
 * 「传送」Tab（协议 §8 / §9 的落点）。
 *
 * 三段结构：**设备**（发现到的对端，点击选中）→ **发送**（系统文件选择器 + 选中设备）→ **任务**
 * （收发进度 / 完成对勾 / 失败抖动）→ **提示**（三项提示音开关，对应协议 §7 默认值）。
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
    val sound by viewModel.soundSettings.collectAsStateWithLifecycle()
    val view = LocalView.current
    var pinInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.ensureStarted() }
    DisposableEffect(view) {
        viewModel.setHapticTarget(view)
        onDispose { viewModel.setHapticTarget(null) }
    }
    LaunchedEffect(pin?.sessionId) { pinInput = "" }

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

    Column(modifier.fillMaxSize()) {
        ScreenHead(title = "局域网互传", meta = meta)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = t.space4, end = t.space4, bottom = t.space12),
            verticalArrangement = Arrangement.spacedBy(t.space3),
        ) {
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
            item {
                Text(
                    "传输任务",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg2,
                    modifier = Modifier.padding(top = t.space2),
                )
            }
            if (tasks.isEmpty()) {
                item { EmptyState(text = "选中设备后发送文件，任务会出现在这里", title = "暂无任务") }
            } else {
                itemsIndexed(tasks, key = { _, task -> task.sessionId + "#" + task.file.fileId }) { index, task ->
                    TaskCard(
                        task = task,
                        index = index,
                        onCancel = { viewModel.cancelTask(task.sessionId) },
                    )
                }
            }
            item {
                SoundSettingsCard(
                    state = sound,
                    onToggle = { enabled, kind -> viewModel.setSound(enabled, kind) },
                )
            }
        }
    }

    val prompt = pin
    if (prompt != null && prompt.direction == TransferDirection.RECV) {
        ConfirmDialog(
            visible = true,
            title = "「${prompt.peerAlias}」请求发送",
            message = "${prompt.fileName} · 在对方设备上输入下方 PIN 即可开始",
            confirmLabel = "知道了",
            dismissLabel = "拒绝",
            onConfirm = viewModel::dismissPin,
            onDismiss = { viewModel.denyIncoming(prompt.sessionId) },
            extraContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(t.space2, Alignment.CenterHorizontally),
                ) {
                    prompt.pin?.forEachIndexed { index, char -> PinDigit(char, index) }
                }
            },
        )
    } else if (prompt != null) {
        val message = prompt.message.ifBlank { "请输入「${prompt.peerAlias}」上显示的 4 位 PIN" }
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
                contentDescription = device.alias,
                tint = if (device.online) t.accent else t.muted,
                modifier = Modifier.size(t.space6),
            )
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.alias, style = MaterialTheme.typography.titleSmall, color = t.fg)
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
                    buildString {
                        append(if (task.direction == TransferDirection.SEND) "发送" else "接收")
                        append(" · ")
                        append(statusLabel(task))
                        append(" · ")
                        append(formatBytes(task.receivedBytes))
                        append(" / ")
                        append(formatBytes(task.file.size))
                    },
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

@Composable
private fun SoundSettingsCard(
    state: TransferViewModel.TransferSoundState,
    onToggle: (Boolean, SoundKind) -> Unit,
) {
    val t = LocalDesignTokens.current
    AppCard {
        Text("提示", style = MaterialTheme.typography.titleSmall, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text(
            "同一事件 500ms 内只提示一次",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space2))
        SoundRow("传输完成", "系统通知音 + 震动", state.complete) {
            onToggle(it, SoundKind.COMPLETE)
        }
        HorizontalDivider(color = t.borderSoft)
        SoundRow("传输失败", "提示音 + 震动（默认关）", state.failure) {
            onToggle(it, SoundKind.FAILURE)
        }
        HorizontalDivider(color = t.borderSoft)
        SoundRow("新设备出现", "仅震动（默认关）", state.newDevice) {
            onToggle(it, SoundKind.NEW_DEVICE)
        }
    }
}

@Composable
private fun SoundRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = t.fg)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        AppSwitch(checked = checked, onCheckedChange = onToggle)
    }
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** 抖动幅度（协议 §7：±4px）。 */
private const val SHAKE_PX = 4f
