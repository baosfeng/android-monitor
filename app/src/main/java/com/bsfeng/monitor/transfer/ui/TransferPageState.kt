package com.bsfeng.monitor.transfer.ui

// 传送页的页面级交互状态与文案（C-01…C-18 用到的浮层 / 瞬时状态，**不进** TransferViewModel）。
//
// 分两层：
//  - [TransferPageState]：只属于这一屏的状态（哪个面板开着、撤销条、toast、焦点回归标记）；
//  - 纯函数：面板标题 / 副行 / 按钮 / 回执条文案 —— 可直接 JVM 单测，UI 里不拼字符串。
//
// 破坏性操作口径（契约第 5 行）：「能撤销给撤销，不能撤销才二次确认」。
//  - 撤销：删除记录（C-05/C-07）→ 5s 撤销条（C-06）；
//  - 二次确认：清空已结束（C-16，不可撤销）、解除配对（C-12）、关闭接收中断传输（C-10 边界）、
//    删除「接收 + 已完成」记录（C-05，会一并删 Downloads 里的文件）。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask

/** 单次发送上限（契约 C-13 边界）。 */
internal const val MAX_FILES_PER_SEND: Int = 50

// ───────────── 取消面板 / 动作面板 ─────────────

/** 取消面板的目标：单个会话的活跃文件，或全部活跃会话（C-09 复用同一面板）。 */
internal data class CancelTarget(
    val sessionIds: List<String>,
    val fileCount: Int,
    val sentBytes: Long,
    val totalBytes: Long,
    val all: Boolean,
)

/**
 * 从任务列表里挑出取消目标。
 *
 * @param sessionId null = 全部活跃会话（C-09）；非 null = 该会话（C-01/C-02）。
 * @return 没有活跃文件时返回 null —— 面板不打开（避免空面板与 0 文件文案）。
 */
internal fun cancelTargetOf(tasks: List<TransferTask>, sessionId: String?): CancelTarget? {
    val active = tasks.filter { it.isActive && (sessionId == null || it.sessionId == sessionId) }
    if (active.isEmpty()) return null
    return CancelTarget(
        sessionIds = active.map { it.sessionId }.distinct(),
        fileCount = active.size,
        sentBytes = active.sumOf { it.receivedBytes },
        totalBytes = active.sumOf { it.file.size },
        all = sessionId == null,
    )
}

/** C-02 / C-09 面板标题。 */
internal fun cancelTitle(target: CancelTarget): String =
    if (target.all) "取消全部 ${target.fileCount} 个传输？" else "取消 ${target.fileCount} 个传输？"

/** C-02 / C-09 面板副行；已传 0 B 时不出现「已传 x」那一段（契约 C-02 边界）。 */
internal fun cancelBody(target: CancelTarget): String =
    if (target.sentBytes <= 0L) {
        "取消后需要重新发送。"
    } else {
        "已传 " + TransferFormat.progressPair(target.sentBytes, target.totalBytes) + "，取消后需要重新发送。"
    }

/** C-02 / C-09 主按钮文案：N=1 时「取消这个文件」。 */
internal fun cancelConfirmLabel(target: CancelTarget): String = when {
    target.all -> "取消全部 ${target.fileCount} 个文件"
    target.fileCount <= 1 -> "取消这个文件"
    else -> "取消 ${target.fileCount} 个文件"
}

/** C-02 成功 toast 文案。 */
internal fun cancelledToast(count: Int): String = "已取消 $count 个文件"

// ───────────── 批次回执条（C-08） ─────────────

/** 同一 sessionId 下的文件数 ≥ 2 才出回执条（单文件批次整条隐藏）。 */
internal fun showsReceipt(tasks: List<TransferTask>): Boolean = tasks.size >= 2

/** 回执条文案：全送达 / 部分失败 / 进行中三态。时间取批次内最近一次更新（本地时区 HH:mm）。 */
internal fun receiptText(tasks: List<TransferTask>, clock: (Long) -> String = ::formatClock): String {
    val done = tasks.count { it.status == TransferStatus.COMPLETED }
    val failed = tasks.count { it.status == TransferStatus.FAILED || it.status == TransferStatus.DENIED }
    val at = clock(tasks.maxOf { it.updatedAtMs })
    return when {
        done == tasks.size -> "${tasks.size} 个文件已全部送达 · $at"
        failed > 0 -> "已送达 $done / ${tasks.size}，$failed 个失败"
        else -> "${tasks.size} 个文件 · 已送达 $done / ${tasks.size}"
    }
}

/** 回执条是否走 success 底（全部送达）。 */
internal fun receiptDelivered(tasks: List<TransferTask>): Boolean =
    tasks.isNotEmpty() && tasks.all { it.status == TransferStatus.COMPLETED }

/** 展开明细的每行左半：完成项「文件已收到 · HH:mm」，其余回落到状态标签。 */
internal fun receiptFileLine(task: TransferTask, clock: (Long) -> String = ::formatClock): String =
    if (task.status == TransferStatus.COMPLETED) {
        "文件已收到 · " + clock(task.updatedAtMs)
    } else {
        statusLabel(task)
    }

// ───────────── 终态卡片副行（契约 §文案口径） ─────────────

/** 单条完成副行：发送「对方已收到 · 14:32」/ 接收「已保存到 Downloads/局域网互传 · 14:32」。 */
internal fun finishedSubtitle(task: TransferTask, clock: (Long) -> String = ::formatClock): String = when {
    task.status == TransferStatus.COMPLETED && task.direction == TransferDirection.SEND ->
        "对方已收到 · " + clock(task.updatedAtMs)
    task.status == TransferStatus.COMPLETED && task.direction == TransferDirection.RECV ->
        "已保存到 Downloads/局域网互传 · " + clock(task.updatedAtMs)
    else -> statusLabel(task) + " · " + clock(task.updatedAtMs)
}

// ───────────── 二次确认文案 ─────────────

/** C-05 / C-07：接收 + 已完成会删 Downloads 里的文件，必须先确认。 */
internal fun needsDeleteConfirm(task: TransferTask): Boolean =
    task.direction == TransferDirection.RECV && task.status == TransferStatus.COMPLETED

internal fun deleteConfirmTitle(fileName: String): String = "删除「$fileName」？"

/** C-05 确认副行（逐字）。 */
internal const val DELETE_CONFIRM_MESSAGE: String =
    "同时会删除 Downloads/局域网互传/ 里的文件，撤销只能恢复记录。"

/** C-16 标题：条数写进标题（契约「清空 24 条记录？」）。 */
internal fun clearConfirmTitle(count: Int): String = "清空 $count 条记录？"

/** C-16 副行：写清后果与将删文件数；「不可撤销」由 [clearConfirmBody] 的「清空后无法恢复」承载。 */
internal fun clearConfirmBody(recordCount: Int, fileCount: Int): String {
    val head = if (fileCount > 0) "其中 $fileCount 条会同时删除 Downloads 里的文件" else "全部是记录，不会动本机文件"
    return "$head，清空后无法恢复（$recordCount 条）。"
}

/** C-12 标题与副行（逐字）。 */
internal fun unpairConfirmTitle(alias: String): String = "解除与「$alias」的配对？"

internal const val UNPAIR_CONFIRM_MESSAGE: String = "解除后再次发送需要输入 4 位 PIN"

/** C-10 边界：关闭接收会中断进行中的传输，先确认。 */
internal const val STOP_RECEIVING_TITLE: String = "关闭接收？"

internal fun stopReceivingBody(activeCount: Int): String =
    "有 $activeCount 个传输进行中，关闭接收会中断它们。"

/** C-18 降级口径：源码无「发现失败」信号，只提供「重新扫描」；扫描中请勿重复点击。 */
internal const val SCAN_IDLE_LABEL: String = "重新扫描"

/** 扫描仍在进行（按钮转圈）。 */
internal const val SCAN_BUSY_LABEL: String = "扫描中"

/** 扫描结束仍无设备时的提示（不谎称失败，只陈述扫描结果）。 */
internal const val SCAN_EMPTY_TOAST: String = "仍未发现设备，检查 Wi-Fi"

// ───────────── 浮层 / 瞬时状态 ─────────────

/** 底部面板：同一时刻只开一个。 */
internal sealed interface TransferSheet {
    /** C-02 / C-03（单批次）与 C-09（全部）共用的取消面板；[trigger] 关闭后焦点回归的锚点。 */
    data class Cancel(val target: CancelTarget, val trigger: String) : TransferSheet

    /** C-04 动作面板：只对终态任务开。 */
    data class Actions(val task: TransferTask, val trigger: String) : TransferSheet
}

/** 二次确认框：破坏性且不可撤销的操作才走这里。 */
internal sealed interface TransferConfirm {
    data class Unpair(val device: PeerDevice, val alias: String) : TransferConfirm
    data class StopReceiving(val activeCount: Int) : TransferConfirm
    data class DeleteReceived(val task: TransferTask) : TransferConfirm
    data class ClearFinished(val recordCount: Int, val fileCount: Int) : TransferConfirm
}

/** 撤销条状态：删除快照 + 窗口起点（5s 收缩进度用）。 */
internal data class TransferUndo(val deletion: DeletedTask, val startedAtMs: Long)

/** toast 信号：同一文案连续出现也要重新计时，因此带自增 id。 */
internal data class TransferToast(val id: Long, val text: String)

/**
 * 传送页的瞬时状态。
 *
 * 只被 Compose 读写，**不持有业务真值**（任务 / 设备 / 开关都在 ViewModel + engine）。
 */
internal class TransferPageState {
    var sheet by mutableStateOf<TransferSheet?>(null)
        private set
    var confirm by mutableStateOf<TransferConfirm?>(null)
        private set
    var undo by mutableStateOf<TransferUndo?>(null)
        private set
    var toast by mutableStateOf<TransferToast?>(null)
        private set

    /** 焦点回归标记：浮层关闭 / 撤销恢复后，由对应控件消费（「移除后五问」之焦点）。 */
    var focusRequest by mutableStateOf<String?>(null)
        private set

    /** 撤销恢复后需要滚动到的任务（sessionId#fileId），由列表消费后清空。 */
    var scrollTarget by mutableStateOf<String?>(null)
        private set

    /** 刚移动到「最近完成」的任务 key：卡片高亮 1.2s（契约 C-02 / C-06）。 */
    var highlight by mutableStateOf<String?>(null)
        private set

    /** 二次确认删除后要落焦的位置（确认框关闭时才生效，避免焦点先跳到未删的行）。 */
    var pendingDeleteFocus by mutableStateOf<String?>(null)
        private set

    /** 「重新扫描」进行中（C-18 降级态）。 */
    var scanning by mutableStateOf(false)
        private set

    /** 接收开关在途（C-10：按钮即时转圈，1s 内落定）。 */
    var receivingBusy by mutableStateOf(false)
        private set

    /** 批量删除（C-16）在途：期间到达的删除事件不弹撤销条（契约明写不可撤销）。 */
    var bulkRemaining by mutableStateOf(0)
        private set

    private var toastSeq = 0L

    fun openSheet(next: TransferSheet) {
        sheet = next
    }

    /** 关闭面板：焦点回到触发控件（C-02/C-03 回 C-01，C-04 回本卡「更多」）。 */
    fun dismissSheet() {
        val trigger = when (val current = sheet) {
            is TransferSheet.Cancel -> current.trigger
            is TransferSheet.Actions -> current.trigger
            null -> null
        }
        sheet = null
        focusRequest = trigger
    }

    fun ask(next: TransferConfirm) {
        confirm = next
    }

    /** 关闭确认框；[trigger] 非空时把焦点还给触发控件。 */
    fun dismissConfirm(trigger: String? = null) {
        confirm = null
        if (trigger != null) focusRequest = trigger
    }

    fun showToast(text: String) {
        toastSeq += 1
        toast = TransferToast(toastSeq, text)
    }

    fun clearToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    fun startUndo(deletion: DeletedTask) {
        undo = TransferUndo(deletion, System.currentTimeMillis())
    }

    fun endUndo() {
        undo = null
    }

    fun requestScroll(target: String) {
        scrollTarget = target
    }

    fun requestFocus(id: String) {
        focusRequest = id
    }

    fun stageDeleteFocus(id: String?) {
        pendingDeleteFocus = id
    }

    /** 消费「删除后焦点」：返回后清空，避免下次确认框误用。 */
    fun takePendingDeleteFocus(): String? {
        val id = pendingDeleteFocus
        pendingDeleteFocus = null
        return id
    }

    fun highlightTask(key: String) {
        highlight = key
    }

    fun clearHighlight(key: String) {
        if (highlight == key) highlight = null
    }

    fun consumeScroll() {
        scrollTarget = null
    }

    fun consumeFocus() {
        focusRequest = null
    }

    fun markScanning(value: Boolean) {
        scanning = value
    }

    fun markReceivingBusy(value: Boolean) {
        receivingBusy = value
    }

    /** 开始批量删除：接下来 [count] 条删除事件按「不可撤销」处理。 */
    fun beginBulkDelete(count: Int) {
        bulkRemaining = count
    }

    /** 消费一条批量删除事件；返回 true 表示这条应立即 commit（不弹撤销条）。 */
    fun consumeBulkDelete(): Boolean {
        if (bulkRemaining <= 0) return false
        bulkRemaining -= 1
        return true
    }

    fun resetBulkDelete() {
        bulkRemaining = 0
    }
}

/** 任务列表项的唯一键（LazyColumn key / 焦点回归 / 滚动定位共用）。 */
internal fun taskKey(task: TransferTask): String = task.sessionId + "#" + task.file.fileId

/** 面板触发点 id：每个终态卡片的「更多」都要能把焦点还回自己。 */
internal fun actionsTriggerOf(task: TransferTask): String = "C-04:" + taskKey(task)

/** 焦点回归 id：撤销恢复后焦点回被恢复的那一行。 */
internal fun taskFocusId(task: TransferTask): String = "task:" + taskKey(task)

/** C-09 锚点 id（进行中分组标题右侧「全部取消」）。 */
internal const val CANCEL_ALL_ID: String = "C-09"

/** C-16 锚点 id（最近完成分组标题右侧「清空已结束」）。 */
internal const val CLEAR_FINISHED_ID: String = "C-16"

/** C-12 触发点 id：确认框关闭后焦点回该设备的「移除配对」。 */
internal fun unpairTriggerOf(deviceId: String): String = "C-12:" + deviceId

/**
 * 「移除后五问」之焦点：浮层关闭 / 状态切换后把焦点还给触发控件。
 *
 * 只在 [pending] 等于本控件 [id] 时请求一次焦点，随后立刻消费（清空），
 * 避免下一次重组把焦点又抢回来。触屏操作时不画焦点环（见 ui/components 的 keyboardFocusRing）。
 */
@Composable
internal fun Modifier.returnFocusWhen(
    id: String,
    pending: String?,
    onConsumed: () -> Unit,
): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(pending, id) {
        if (pending == id) {
            runCatching { requester.requestFocus() }
            onConsumed()
        }
    }
    return focusRequester(requester)
}
