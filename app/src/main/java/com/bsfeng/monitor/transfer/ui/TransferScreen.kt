package com.bsfeng.monitor.transfer.ui

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

/**
 * 「传送」Tab（协议 §8 / §9 的落点）。
 *
 * 四段结构：**可被发现**（发现开关 + 免配对窗口倒计时）→ **附近设备**（点选发送目标）→
 * **进行中**（进度 / 速度 / 取消）→ **最近完成**（体积 / 已终结任务可左右滑动删除 + 5s 撤销）。
 *
 * 提示音、免配对窗口时长、体积阈值、接收目录清理都收敛到「设置 → 局域网互传」，本页不再放设置项。
 *
 * 动效全部取 `DesignTokenTable` 的 `dur-*` / `ease-*`（含 reduced-motion 兜底）：
 * 设备与任务入场 = `Modifier.staggerEnterAt`（`dur-page` + `ease-out` + 逐项 `stagger`），
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
    val autoCloseDeadline by viewModel.autoCloseDeadline.collectAsStateWithLifecycle()
    val alias by viewModel.aliasFlow.collectAsStateWithLifecycle()
    val view = LocalView.current
    var pinInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

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
    // 「自动关闭接收」剩余时间每秒刷新；未计时 / 未运行时归零（不展示这一行）
    var autoCloseRemainingMs by remember { mutableStateOf(0L) }
    LaunchedEffect(autoCloseDeadline, running) {
        while (running && autoCloseDeadline > 0L) {
            autoCloseRemainingMs = (autoCloseDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
            if (autoCloseRemainingMs <= 0L) break
            delay(1_000L)
        }
        if (!running || autoCloseDeadline <= 0L) autoCloseRemainingMs = 0L
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
    // 关闭态不出现端口号：端口只在服务真的在跑时才有意义（顶部副标题是状态，不是调试信息）。
    val meta = buildString {
        if (running) {
            append("端口 ${viewModel.localPort} · 在线 $onlineCount 台")
        } else {
            append("未开启接收")
        }
        append(" · ${alias}")
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 固定头部：实色底 + 明确高度，列表用 weight(1f) 占剩余空间，滚动时不会压到头部
            Box(Modifier.fillMaxWidth().background(t.bg)) {
                ScreenHead(title = "局域网互传", meta = meta)
            }
            TransferContentList(
                viewModel = viewModel,
                t = t,
                devices = devices,
                tasks = tasks,
                selected = selected,
                running = running,
                onlineCount = onlineCount,
                backgroundReceive = backgroundReceive,
                windowRemainingMs = windowRemainingMs,
                autoCloseRemainingMs = autoCloseRemainingMs,
                thresholdMb = thresholdMb,
                onPickFiles = { picker.launch("*/*") },
            )
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

    TransferPinPromptSection(
        prompt = pin,
        pinInput = pinInput,
        onPinInputChange = { pinInput = it },
        onDismissPin = viewModel::dismissPin,
        onDeny = { viewModel.denyIncoming(it) },
        onSubmitPin = { viewModel.submitPin(it) },
    )
}
