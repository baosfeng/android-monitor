package com.bsfeng.monitor.transfer.ui

// 传送页的滚动内容区：接收卡 → 附近设备 → 发送按钮 → 进行中 → 最近完成。
//
// 列表结构先摊平成 [TransferRow] 再交给 LazyColumn：这样「撤销后滚动到被恢复的行」
// （锚点 C-06）与「LazyColumn key 稳定」都能按行 key 直接定位，不靠 index 心算。
// 行拼装是纯函数 [buildTransferRows]，单测直接覆盖分组 / 计数 / 回执条去留。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import com.bsfeng.monitor.transfer.service.ResendAvailability
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 列表行类型。 */
internal data class TransferRow(
    val key: String,
    val kind: Kind,
    val title: String = "",
    val titleAction: TitleAction? = null,
    val device: PeerDevice? = null,
    val deviceIndex: Int = 0,
    val tasks: List<TransferTask> = emptyList(),
    val task: TransferTask? = null,
    val hint: String = "",
) {
    /** 行类型。 */
    enum class Kind { RECEIVING, TITLE, DEVICES_EMPTY, DEVICE, SEND, RECEIPT, TASK, HINT }

    /** 分组标题右侧的动作。 */
    enum class TitleAction { CANCEL_ALL, CLEAR_FINISHED }
}

/** 「进行中 · N」之外的「最近完成」标题（锚点 C-16 的 N 与展示条数一致）。 */
internal fun finishedTitle(count: Int): String = if (count == 0) "最近完成" else "最近完成 · $count"

/** 附近设备标题。 */
internal const val DEVICES_TITLE: String = "附近设备"

/** 空分组提示文案（逐字）。 */
internal const val ACTIVE_EMPTY_HINT: String = "暂无进行中的传输"

/** 最近完成空态文案（逐字）。 */
internal const val FINISHED_EMPTY_HINT: String = "完成的传输会留在这里"

/**
 * 摊平列表行。
 *
 * 回执条（C-08）按 sessionId 分组后插在对应任务卡之前；单文件批次不出条。
 * 「全部取消」（C-09）只在活跃会话 ≥ 2 时出现（避免与卡内 C-01 重复）；
 * 「清空已结束」（C-16）只在终态记录 ≥ 2 时出现。
 */
internal fun buildTransferRows(
    devices: List<PeerDevice>,
    tasks: List<TransferTask>,
): List<TransferRow> {
    val rows = mutableListOf<TransferRow>()
    rows += TransferRow("receiving", TransferRow.Kind.RECEIVING)
    rows += TransferRow("title-devices", TransferRow.Kind.TITLE, title = DEVICES_TITLE)
    if (devices.isEmpty()) {
        rows += TransferRow("devices-empty", TransferRow.Kind.DEVICES_EMPTY)
    } else {
        devices.forEachIndexed { index, device ->
            rows += TransferRow("device-${device.deviceId}", TransferRow.Kind.DEVICE, device = device, deviceIndex = index)
        }
    }
    rows += TransferRow("send", TransferRow.Kind.SEND)

    val active = TransferTaskGroups.active(tasks)
    val finished = TransferTaskGroups.finished(tasks)
    rows += TransferRow(
        key = "title-active",
        kind = TransferRow.Kind.TITLE,
        title = TransferTaskGroups.activeTitle(tasks),
        titleAction = if (active.map { it.sessionId }.distinct().size >= 2) {
            TransferRow.TitleAction.CANCEL_ALL
        } else {
            null
        },
    )
    if (active.isEmpty()) {
        rows += TransferRow("hint-active", TransferRow.Kind.HINT, hint = ACTIVE_EMPTY_HINT)
    } else {
        appendTaskRows(rows, active, prefix = "active")
    }

    rows += TransferRow(
        key = "title-finished",
        kind = TransferRow.Kind.TITLE,
        title = finishedTitle(finished.size),
        titleAction = if (finished.size >= 2) TransferRow.TitleAction.CLEAR_FINISHED else null,
    )
    val shown = finished.take(MAX_FINISHED_TASKS)
    if (shown.isEmpty()) {
        rows += TransferRow("hint-finished", TransferRow.Kind.HINT, hint = FINISHED_EMPTY_HINT)
    } else {
        appendTaskRows(rows, shown, prefix = "finished")
    }
    return rows
}

/** 任务行：同 sessionId 且 ≥ 2 个文件时先插一条回执条。 */
private fun appendTaskRows(rows: MutableList<TransferRow>, tasks: List<TransferTask>, prefix: String) {
    tasks.groupBy { it.sessionId }.forEach { (sessionId, group) ->
        if (showsReceipt(group)) {
            rows += TransferRow("$prefix-receipt-$sessionId", TransferRow.Kind.RECEIPT, tasks = group)
        }
        group.forEach { task ->
            rows += TransferRow("$prefix-task-" + taskKey(task), TransferRow.Kind.TASK, task = task)
        }
    }
}

/** 内容区。 */
@Composable
internal fun TransferContentList(
    viewModel: TransferViewModel,
    page: TransferPageState,
    t: DesignTokens,
    listState: LazyListState,
    devices: List<PeerDevice>,
    tasks: List<TransferTask>,
    selected: String?,
    running: Boolean,
    receivingBusy: Boolean,
    onlineCount: Int,
    backgroundReceive: Boolean,
    windowRemainingMs: Long,
    autoCloseRemainingMs: Long,
    thresholdMb: Int,
    scanning: Boolean,
    onPickFiles: () -> Unit,
    onScan: () -> Unit,
    onStartReceiving: () -> Unit,
    onStopReceiving: () -> Unit,
) {
    val rows = buildTransferRows(devices, tasks)
    // 可重试任务的预计算表：由 VM 在任务集合变化时算好，列表侧只读（避免组合期做 ContentResolver IO）
    val resendReadyIds by viewModel.resendReadyIds.collectAsState()
    val expandedReceipts = remember { mutableStateMapOf<String, Boolean>() }
    // C-06：撤销恢复后滚动到被恢复的行（行 key = 任务 key，直接按 key 找下标）
    LaunchedEffect(page.scrollTarget, rows) {
        val target = page.scrollTarget ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == target || taskKeyOf(it) == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            page.consumeScroll()
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = t.space4,
            end = t.space4,
            top = t.space2,
            bottom = t.space12,
        ),
        verticalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row.kind) {
                TransferRow.Kind.RECEIVING -> ReceivingCard(
                    running = running,
                    port = viewModel.localPort,
                    onlineCount = onlineCount,
                    backgroundReceive = backgroundReceive,
                    windowRemainingMs = windowRemainingMs,
                    autoCloseRemainingMs = autoCloseRemainingMs,
                    thresholdMb = thresholdMb,
                    busy = receivingBusy,
                    focusPending = page.focusRequest,
                    onFocusConsumed = page::consumeFocus,
                    onStart = onStartReceiving,
                    onStop = onStopReceiving,
                )
                TransferRow.Kind.TITLE -> GroupTitleRow(
                    text = row.title,
                    action = row.titleAction,
                    focusId = row.key,
                    t = t,
                    focusPending = page.focusRequest,
                    onFocusConsumed = page::consumeFocus,
                    onCancelAll = {
                        val target = cancelTargetOf(tasks, sessionId = null) ?: return@GroupTitleRow
                        page.openSheet(TransferSheet.Cancel(target, trigger = CANCEL_ALL_ID))
                    },
                    onClearFinished = {
                        page.ask(
                            TransferConfirm.ClearFinished(
                                recordCount = TransferTaskGroups.finished(tasks).size,
                                fileCount = receivedCompletedCount(tasks),
                            ),
                        )
                    },
                )
                TransferRow.Kind.DEVICES_EMPTY -> DevicesEmptyBlock(
                    scanning = scanning,
                    onScan = onScan,
                    focusPending = page.focusRequest,
                    onFocusConsumed = page::consumeFocus,
                )
                TransferRow.Kind.DEVICE -> DeviceRow(
                    device = row.device ?: return@items,
                    selected = selected == row.device?.deviceId,
                    paired = viewModel.pairedDeviceIds().contains(row.device?.deviceId),
                    index = row.deviceIndex,
                    focusPending = page.focusRequest,
                    onFocusConsumed = page::consumeFocus,
                    onClick = { viewModel.selectDevice(row.device?.deviceId) },
                    onRemovePairing = {
                        val device = row.device ?: return@DeviceRow
                        page.ask(TransferConfirm.Unpair(device, device.alias.trim().ifBlank { "未知设备" }))
                    },
                )
                TransferRow.Kind.SEND -> SendFilesButton(
                    state = sendButtonState(devices, selected),
                    onPickFiles = onPickFiles,
                    focusPending = page.focusRequest,
                    onFocusConsumed = page::consumeFocus,
                )
                TransferRow.Kind.RECEIPT -> {
                    // 可重试的失败项：发送方向 + 终态非完成 + 源文件仍可读（锚点 C-08 边界）。
                    // 可用性由 VM 在收据批次变化时**预计算**成表，这里只读结果 ——
                    // 直接调 resendAvailability 会在 items 体里逐任务 openFileDescriptor，
                    // 而 items 体每次重组、每次滚动回收都会重跑一遍，等于组合期做 ContentResolver IO。
                    val retryable = row.tasks.filter { task ->
                        task.direction == TransferDirection.SEND &&
                            task.status.isTerminal &&
                            task.status != TransferStatus.COMPLETED &&
                            (task.sessionId + "/" + task.file.fileId) in resendReadyIds
                    }
                    BatchReceiptBar(
                        tasks = row.tasks,
                        expanded = expandedReceipts[row.key] == true,
                        onToggle = { expandedReceipts[row.key] = expandedReceipts[row.key] != true },
                        retryable = retryable,
                        onRetryFailed = {
                            if (retryable.isEmpty()) return@BatchReceiptBar

                            // 逐条重发：成功的自行出现在「进行中」，失败的按口径口径 toast 一次
                            var failedReason: String? = null
                            var started = 0
                            retryable.forEach { task ->
                                viewModel.resendTask(task) { result ->
                                    if (result.isSuccess) started++
                                    else failedReason = result.exceptionOrNull()?.message
                                }
                            }
                            page.showToast(
                                if (started > 0 || failedReason == null) {
                                    "正在重试 " + retryable.size + " 个失败项"
                                } else {
                                    failedReason ?: "重试失败"
                                },
                            )
                            page.requestScroll("hint-active")
                        },
                    )
                }
                TransferRow.Kind.TASK -> {
                    val task = row.task ?: return@items
                    if (task.isActive) {
                        SwipeableTaskCard(
                            task = task,
                            onCancel = { page.openSheet(TransferSheet.Cancel(cancelTargetOf(tasks, task.sessionId) ?: return@SwipeableTaskCard, trigger = "C-01:" + taskKey(task))) },
                            onDelete = {},
                            onMore = {},
                            canDelete = false,
                            requiresConfirm = false,
                            highlight = page.highlight == taskKey(task),
                            onHighlightDone = { page.clearHighlight(taskKey(task)) },
                            focusPending = page.focusRequest,
                            onFocusConsumed = page::consumeFocus,
                        )
                    } else {
                        SwipeableTaskCard(
                            task = task,
                            onCancel = {},
                            onDelete = {
                                requestDelete(
                                    page = page,
                                    task = task,
                                    nextFocus = nextFocusAfterDelete(tasks, task),
                                    onDelete = { viewModel.deleteTask(it.sessionId, it.file.fileId) },
                                )
                            },
                            onMore = { page.openSheet(TransferSheet.Actions(task, trigger = actionsTriggerOf(task))) },
                            canDelete = true,
                            requiresConfirm = needsDeleteConfirm(task),
                            highlight = page.highlight == taskKey(task),
                            onHighlightDone = { page.clearHighlight(taskKey(task)) },
                            focusPending = page.focusRequest,
                            onFocusConsumed = page::consumeFocus,
                        )
                    }
                }
                TransferRow.Kind.HINT -> HintText(row.hint)
            }
        }
    }
}

private fun taskKeyOf(row: TransferRow): String? = row.task?.let { taskKey(it) }

/** 接收 + 已完成条数（C-16 确认框里「会删 Downloads 文件」的条数）。 */
internal fun receivedCompletedCount(tasks: List<TransferTask>): Int = tasks.count {
    it.direction == com.bsfeng.monitor.domain.transfer.TransferDirection.RECV &&
        it.status == com.bsfeng.monitor.domain.transfer.TransferStatus.COMPLETED
}

/** 删除入口（C-05 / C-07 共用）：接收 + 已完成先二次确认，其余直接删。 */
internal fun requestDelete(
    page: TransferPageState,
    task: TransferTask,
    nextFocus: String?,
    onDelete: (TransferTask) -> Unit,
) {
    page.dismissSheet()
    // 面板关闭时置上的焦点回归标记先消费掉：本次是删除，行随即消失，不能让焦点闪回已删节点
    page.consumeFocus()
    if (needsDeleteConfirm(task)) {
        // 先确认再删：焦点去向暂存，确认框关闭后落焦（锚点 C-05「焦点落到下一条」）
        page.stageDeleteFocus(nextFocus)
        page.ask(TransferConfirm.DeleteReceived(task))
        return
    }
    onDelete(task)
    if (nextFocus != null) page.requestFocus(nextFocus)
}

/**
 * 删除后焦点去向（锚点 C-05「记录行移除后焦点落到下一条；最后一条落到分组标题」）。
 *
 * 按「最近完成」的展示顺序（与列表一致）取被删行的下一条；没有下一条时落到分组标题。
 */
internal fun nextFocusAfterDelete(tasks: List<TransferTask>, task: TransferTask): String {
    val shown = TransferTaskGroups.finished(tasks).take(MAX_FINISHED_TASKS)
    val index = shown.indexOfFirst { it.sessionId == task.sessionId && it.file.fileId == task.file.fileId }
    val next = if (index >= 0) shown.drop(index + 1).firstOrNull() else null
    return next?.let { taskFocusId(it) } ?: "title-finished"
}

/** 分组标题 + 右侧动作（C-09 / C-16）。 */
@Composable
private fun GroupTitleRow(
    text: String,
    action: TransferRow.TitleAction?,
    focusId: String,
    t: DesignTokens,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.fg2,
            modifier = Modifier
                .weight(1f)
                .returnFocusWhen(focusId, focusPending, onFocusConsumed)
                .focusable(),
        )
        when (action) {
            TransferRow.TitleAction.CANCEL_ALL -> TitleActionButton(
                label = "全部取消",
                tag = CANCEL_ALL_ID,
                danger = true,
                focusPending = focusPending,
                onFocusConsumed = onFocusConsumed,
                modifier = Modifier.testTag("C-09"),
                onClick = onCancelAll,
            )
            TransferRow.TitleAction.CLEAR_FINISHED -> TitleActionButton(
                label = "清空已结束",
                tag = CLEAR_FINISHED_ID,
                danger = false,
                focusPending = focusPending,
                onFocusConsumed = onFocusConsumed,
                modifier = Modifier.testTag("C-16"),
                onClick = onClearFinished,
            )
            null -> Unit
        }
    }
}

/** 分组标题右侧文字动作：点击区 ≥ 44dp，按下 0.96。 */
@Composable
private fun TitleActionButton(
    label: String,
    tag: String,
    danger: Boolean,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Box(
        modifier = Modifier
            .height(t.tapMin)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(interactionSource = interaction, indication = null) {
                haptic(if (danger) HapticTone.REJECT else HapticTone.LIGHT)
                onClick()
            }
            .then(modifier)
            .returnFocusWhen(tag, focusPending, onFocusConsumed)
            .padding(horizontal = t.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (danger) t.dangerText else t.accent,
            modifier = Modifier.testTag("button.title.action"),
        )
    }
}

/**
 * 附近设备空态：C-18 降级口径 —— 源码没有「发现失败」信号，这里只表达「暂无设备」两态（空闲 / 扫描中），
 * 按钮叫「重新扫描」而不是「重试发现」（没失败过，不能说重试）。
 */
@Composable
private fun DevicesEmptyBlock(
    scanning: Boolean,
    onScan: () -> Unit,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth()) {
        EmptyState(
            text = "同一 Wi-Fi 下打开 Mac 端「传送」即可互相发现",
            title = "暂无设备",
            icon = Icons.Outlined.Devices,
            actionLabel = if (scanning) SCAN_BUSY_LABEL else SCAN_IDLE_LABEL,
            onAction = { if (!scanning) onScan() },
            actionModifier = Modifier
                .testTag("C-18")
                .returnFocusWhen("C-18", focusPending, onFocusConsumed),
        )
        if (scanning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(t.space4),
                    strokeWidth = 2.dp,
                    color = t.accent,
                )
                Spacer(Modifier.width(t.space2))
                Text("正在扫描附近设备…", style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
    }
}

/** 发送按钮三态（锚点 C-13 边界 + C-11 边界）。 */
internal enum class SendButtonState { NO_DEVICE, OFFLINE, READY }

/**
 * 发送按钮状态：没选设备 → 先选；选中的设备离线 → 提示重新选；都在线 → 可选文件。
 *
 * C-11 边界：选中设备离线时发送按钮禁用并提示「对方已离线，请重新选择」。
 */
internal fun sendButtonState(devices: List<PeerDevice>, selectedId: String?): SendButtonState = when {
    selectedId == null -> SendButtonState.NO_DEVICE
    devices.firstOrNull { it.deviceId == selectedId }?.online != true -> SendButtonState.OFFLINE
    else -> SendButtonState.READY
}

/** 发送按钮文案（逐字）。 */
internal fun sendButtonLabel(state: SendButtonState): String = when (state) {
    SendButtonState.NO_DEVICE -> "先选择一台设备"
    SendButtonState.OFFLINE -> "对方已离线，请重新选择"
    SendButtonState.READY -> "选择文件发送"
}

/** C-13 发送按钮：禁用态改文案；按下 0.96 + MEDIUM 触感。 */
@Composable
private fun SendFilesButton(
    state: SendButtonState,
    onPickFiles: () -> Unit,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val enabled = state == SendButtonState.READY
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .pressFeedback(interaction, enabled = enabled)
            .clip(shape)
            .background(if (enabled) t.accent else t.fgFade(10))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                haptic(HapticTone.MEDIUM)
                onPickFiles()
            }
            .testTag("C-13")
            .returnFocusWhen("C-13", focusPending, onFocusConsumed),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Upload,
            contentDescription = null,
            tint = if (enabled) t.accentOn else t.meta,
            modifier = Modifier.size(t.space5),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            sendButtonLabel(state),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) t.accentOn else t.meta,
        )
    }
}
