package com.bsfeng.monitor.transfer.ui

// 「传送」Tab（契约 docs/交互设计/s22-局域网互传-交互契约.md，锚点 C-01…C-18）。
//
// 四段结构：**接收状态**（C-10）→ **附近设备**（C-11 选中 / C-12 移除配对 / C-18 重新扫描）→
// **发送**（C-13）→ **进行中**（C-01 取消 → C-02 / C-03 面板，C-09 全部取消，C-08 回执条）→
// **最近完成**（C-04 更多 → C-05 / C-17 动作面板，C-07 滑动删除，C-06 撤销条，C-16 清空已结束）。
//
// 本文件只做编排：数据真值在 TransferViewModel + TransferEngine，瞬时状态在 [TransferPageState]，
// 文案在 TransferPageState 的纯函数里，浮层在 TransferSheets / TransferUndoBar。
//
// 破坏性操作口径：「能撤销给撤销（C-05/C-07 → C-06 5s 撤销条），不能撤销才二次确认
// （C-16 清空 / C-12 解除配对 / C-10 关闭接收中断传输 / C-05 接收完成项删文件）」。

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 接收开关的落定窗口（契约 C-10：按钮即时转圈，1s 内完成）。 */
private const val RECEIVING_SETTLE_MS: Long = 1_000L

/** 重新扫描的落定窗口（契约 C-18 降级口径：800ms 内给出结果）。 */
private const val SCAN_SETTLE_MS: Long = 800L

/** 批量删除事件的兜底复位（事件少于预期时不能永久抑制撤销条）。 */
private const val BULK_DELETE_RESET_MS: Long = 3_000L

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
    val openPairing by viewModel.openPairing.collectAsStateWithLifecycle()
    val thresholdMb by viewModel.autoApproveThresholdMb.collectAsStateWithLifecycle()
    val backgroundReceive by viewModel.backgroundReceive.collectAsStateWithLifecycle()
    val autoCloseDeadline by viewModel.autoCloseDeadline.collectAsStateWithLifecycle()
    val alias by viewModel.aliasFlow.collectAsStateWithLifecycle()
    val view = LocalView.current
    val page = remember { TransferPageState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pinInput by remember { mutableStateOf("") }

    DisposableEffect(view) {
        viewModel.setHapticTarget(view)
        onDispose { viewModel.setHapticTarget(null) }
    }
    LaunchedEffect(pin?.sessionId) { pinInput = "" }

    // 免配对窗口剩余时间每秒刷新（到期后文案自行消失）
    var windowRemainingMs by remember { mutableLongStateOf(openPairing.remainingMs()) }
    LaunchedEffect(openPairing) {
        while (true) {
            windowRemainingMs = openPairing.remainingMs()
            if (windowRemainingMs <= 0L) break
            delay(1_000L)
        }
    }
    // 「自动关闭接收」剩余时间每秒刷新；未计时 / 未运行时归零（不展示这一行）
    var autoCloseRemainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(autoCloseDeadline, running) {
        while (running && autoCloseDeadline > 0L) {
            autoCloseRemainingMs = (autoCloseDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
            if (autoCloseRemainingMs <= 0L) break
            delay(1_000L)
        }
        if (!running || autoCloseDeadline <= 0L) autoCloseRemainingMs = 0L
    }

    /** C-10：开启接收（落定时长内没有 running=true 就按「端口被占用」提示）。 */
    fun startReceivingNow() {
        if (page.receivingBusy) return
        page.markReceivingBusy(true)
        viewModel.startReceiving()
        scope.launch {
            val started = withTimeoutOrNull(RECEIVING_SETTLE_MS) {
                viewModel.running.first { it }
                true
            } ?: false
            page.markReceivingBusy(false)
            if (!started) page.showToast("端口被占用，开启失败")
            page.requestFocus(RECEIVING_TOGGLE_ID)
        }
    }

    /** C-10：关闭接收（有传输中任务时由调用方先确认）。 */
    fun stopReceivingNow() {
        if (page.receivingBusy) return
        page.markReceivingBusy(true)
        viewModel.stopReceiving()
        scope.launch {
            withTimeoutOrNull(RECEIVING_SETTLE_MS) {
                viewModel.running.first { !it }
                true
            }
            page.markReceivingBusy(false)
            page.requestFocus(RECEIVING_TOGGLE_ID)
        }
    }

    /** C-18 降级口径：重新扫描 = 重启一次发现线程（源码没有独立扫描入口，见交接文档）。 */
    fun scanNow() {
        if (page.scanning) return
        page.markScanning(true)
        scope.launch {
            viewModel.setDiscoverable(false)
            delay(120)
            viewModel.setDiscoverable(true)
            delay(SCAN_SETTLE_MS)
            page.markScanning(false)
            if (viewModel.devices.value.isEmpty()) page.showToast(SCAN_EMPTY_TOAST)
            page.requestFocus("C-18")
        }
    }

    /** C-02 / C-09：确认取消。engine 的取消是会话级，逐会话调用。 */
    fun confirmCancel(target: CancelTarget) {
        target.sessionIds.forEach { viewModel.cancelTask(it) }
        page.dismissSheet()
        page.showToast(cancelledToast(target.fileCount))
        // 归属：任务随状态转 CANCELLED 落进「最近完成」；高亮并把焦点交给该卡的「更多」。
        // 注意：C-02 要求「滚动位置不变」，因此这里不请求滚动；只有 C-09（全部取消）滚到分组空态。
        val moved = tasks.firstOrNull { it.sessionId in target.sessionIds && it.isActive }
        if (moved != null) {
            page.highlightTask(taskKey(moved))
            page.requestFocus(actionsTriggerOf(moved))
        }
        if (target.all) page.requestScroll("hint-active")
    }

    /** C-06：撤销删除（恢复行 + 高亮 + 滚动 + 焦点）。 */
    fun undoDelete() {
        val current = page.undo ?: return
        page.endUndo()
        val restored = viewModel.undoDelete(current.deletion.deletionId) ?: return
        if (restored.fileDeleted) page.showToast("文件已删除，仅恢复记录")
        page.requestScroll(taskKey(restored.task))
        page.requestFocus(taskFocusId(restored.task))
        page.highlightTask(taskKey(restored.task))
    }

    /** C-16：清空已结束（不可撤销 → 立即 commit 抑制撤销条）。 */
    fun clearFinished() {
        val terminal = TransferTaskGroups.finished(viewModel.tasks.value)
        page.beginBulkDelete(terminal.size)
        terminal.forEach { viewModel.deleteTask(it.sessionId, it.file.fileId) }
        page.dismissConfirm()
        page.requestFocus("title-finished")
        page.showToast("已清空记录")
        scope.launch {
            delay(BULK_DELETE_RESET_MS)
            page.resetBulkDelete()
        }
    }

    // 删除事件 → 撤销条（批量删除场景直接 commit，不弹条）
    LaunchedEffect(viewModel) {
        viewModel.deletedTasks.collect { deleted ->
            if (page.consumeBulkDelete()) {
                viewModel.commitDelete(deleted.deletionId)
                return@collect
            }
            // 连续删除：后一条覆盖前一条 → 先把前一条提交（窗口外不可恢复）
            page.undo?.let { viewModel.commitDelete(it.deletion.deletionId) }
            page.startUndo(deleted)
            page.showToast("已删除记录")
        }
    }
    // 撤销窗口到期：提交删除快照
    LaunchedEffect(page.undo?.deletion?.deletionId) {
        val current = page.undo ?: return@LaunchedEffect
        delay(viewModel.undoWindowMs)
        if (page.undo?.deletion?.deletionId == current.deletion.deletionId) {
            viewModel.commitDelete(current.deletion.deletionId)
            page.endUndo()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        // 选择器取消返回：不建任务、不出 toast（契约 C-13）
        if (uris.isNotEmpty()) {
            if (uris.size > MAX_FILES_PER_SEND) {
                page.showToast("最多一次发送 $MAX_FILES_PER_SEND 个文件")
            } else {
                viewModel.sendFiles(uris)
            }
        }
        page.requestFocus("C-13")
    }

    val onlineCount = devices.count { it.online }
    // 关闭态不出现端口号：端口只在服务真的在跑时才有意义（顶部副标题是状态，不是调试信息）。
    val meta = buildString {
        if (running) {
            append("端口 ${viewModel.localPort} · 在线 $onlineCount 台")
        } else {
            append("未开启接收")
        }
        append(" · $alias")
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 固定头部：实色底 + 明确高度，列表用 weight(1f) 占剩余空间，滚动时不会压到头部
            Box(Modifier.fillMaxWidth().background(t.bg)) {
                ScreenHead(title = "局域网互传", meta = meta)
            }
            TransferContentList(
                viewModel = viewModel,
                page = page,
                t = t,
                listState = listState,
                devices = devices,
                tasks = tasks,
                selected = selected,
                running = running,
                receivingBusy = page.receivingBusy,
                onlineCount = onlineCount,
                backgroundReceive = backgroundReceive,
                windowRemainingMs = windowRemainingMs,
                autoCloseRemainingMs = autoCloseRemainingMs,
                thresholdMb = thresholdMb,
                scanning = page.scanning,
                onPickFiles = { picker.launch("*/*") },
                onScan = ::scanNow,
                onStartReceiving = ::startReceivingNow,
                onStopReceiving = {
                    val activeCount = TransferTaskGroups.active(tasks).size
                    if (activeCount > 0) {
                        page.ask(TransferConfirm.StopReceiving(activeCount))
                    } else {
                        stopReceivingNow()
                    }
                },
            )
        }
        // 底部：toast（1.6s）在上，撤销条（5s + 收缩进度）在下
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = t.space4, vertical = t.space3),
            verticalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            AppToast(
                message = page.toast?.text,
                onDismiss = { page.toast?.let { page.clearToast(it.id) } },
                modifier = Modifier.fillMaxWidth(),
            )
            TransferUndoBar(
                undo = page.undo,
                windowMs = viewModel.undoWindowMs,
                onUndo = ::undoDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 面板：取消（C-02 / C-03 / C-09）与动作面板（C-04 → C-05 / C-17）
    when (val sheet = page.sheet) {
        is TransferSheet.Cancel -> CancelSheet(
            target = sheet.target,
            onConfirm = { confirmCancel(sheet.target) },
            onContinue = page::dismissSheet,
        )
        is TransferSheet.Actions -> ActionsSheet(
            task = sheet.task,
            batchCount = tasks.count { it.sessionId == sheet.task.sessionId },
            onDelete = {
                requestDelete(
                    page = page,
                    task = sheet.task,
                    nextFocus = nextFocusAfterDelete(tasks, sheet.task),
                    onDelete = { viewModel.deleteTask(it.sessionId, it.file.fileId) },
                )
            },
            onDismiss = page::dismissSheet,
        )
        null -> Unit
    }

    // 二次确认：解除配对（C-12）/ 关闭接收（C-10）/ 删除接收完成项（C-05）/ 清空已结束（C-16）
    TransferConfirmHost(
        confirm = page.confirm,
        onConfirm = {
            when (val confirm = page.confirm) {
                is TransferConfirm.Unpair -> {
                    viewModel.removePairing(confirm.device.deviceId)
                    page.dismissConfirm(unpairTriggerOf(confirm.device.deviceId))
                }
                is TransferConfirm.StopReceiving -> {
                    page.dismissConfirm(RECEIVING_TOGGLE_ID)
                    stopReceivingNow()
                }
                is TransferConfirm.DeleteReceived -> {
                    viewModel.deleteTask(confirm.task.sessionId, confirm.task.file.fileId)
                    page.dismissConfirm()
                    page.takePendingDeleteFocus()?.let(page::requestFocus)
                }
                is TransferConfirm.ClearFinished -> clearFinished()
                null -> Unit
            }
        },
        onDismiss = {
            // 关闭确认框：焦点回触发控件（清空按钮 / 关闭接收 / 移除配对）；删除确认例外，见 C-05
            val trigger = when (page.confirm) {
                is TransferConfirm.ClearFinished -> CLEAR_FINISHED_ID
                is TransferConfirm.StopReceiving -> RECEIVING_TOGGLE_ID
                is TransferConfirm.Unpair -> (page.confirm as TransferConfirm.Unpair).let {
                    unpairTriggerOf(it.device.deviceId)
                }
                is TransferConfirm.DeleteReceived -> {
                    // 取消删除：行还在 → 焦点回本卡「更多」；暂存的「下一条」焦点丢弃
                    page.takePendingDeleteFocus()
                    actionsTriggerOf((page.confirm as TransferConfirm.DeleteReceived).task)
                }
                null -> null
            }
            page.dismissConfirm(trigger)
        },
    )

    TransferPinPromptSection(
        prompt = pin,
        pinInput = pinInput,
        onPinInputChange = { pinInput = it },
        onDismissPin = viewModel::dismissPin,
        onDeny = { viewModel.denyIncoming(it) },
        onSubmitPin = { viewModel.submitPin(it) },
        onTimeout = { page.showToast(PIN_TIMEOUT_TEXT) },
    )
}
