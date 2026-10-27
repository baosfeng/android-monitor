package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：任务列表的写入与历史加载（TransferEngine 的扩展）。
// 落库口径：首次出现与进入终态各写一次，进度推进不写库。

import android.util.Log
import androidx.core.net.toUri
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun TransferEngine.upsertTask(task: TransferTask) {
    _tasks.update { current ->
        val index = current.indexOfFirst {
            it.sessionId == task.sessionId && it.file.fileId == task.file.fileId
        }
        if (index < 0) {
            maybePersist(previous = null, updated = task)
            listOf(task) + current
        } else {
            val previous = current[index]
            maybePersist(previous = previous, updated = task)
            current.toMutableList().also { it[index] = task }
        }
    }
}

internal fun TransferEngine.patchTask(
    sessionId: String,
    fileId: String,
    transform: (TransferTask) -> TransferTask,
) {
    _tasks.update { current ->
        val index = current.indexOfFirst {
            it.sessionId == sessionId && it.file.fileId == fileId
        }
        if (index < 0) {
            current
        } else {
            val previous = current[index]
            val updated = transform(previous)
            maybePersist(previous = previous, updated = updated)
            current.toMutableList().also { it[index] = updated }
        }
    }
}

/**
 * 任务历史落库：**首次出现**与**进入终态**各写一次，进度推进不写库
 * （64 KiB 一块的进度每块写一次 SQLite 不划算，重启后未完成的任务按失败处理）。
 */
private fun TransferEngine.maybePersist(previous: TransferTask?, updated: TransferTask) {
    val firstSeen = previous == null
    val becameTerminal = updated.status.isTerminal && (previous == null || !previous.status.isTerminal)
    if (!firstSeen && !becameTerminal) return
    persistTask(updated, uriForPersist(updated))
}

/**
 * 这一行要落库的 Uri：**按方向**取，别再把两侧混成一份。
 *
 * 进程内曾只取 [TransferEngine.publishedFiles]（接收落盘产物），于是发送任务的 Uri 恒为 null：
 * 发送时源 Uri 只活在 sendFiles 的协程里，一次用完就没了 ——「重新发送」（C-17）与
 * 「重试失败项」（C-08）因此只能恒不渲染。现在发送方向落 [TransferEngine.sourceFiles]。
 */
internal fun TransferEngine.uriForPersist(task: TransferTask): String? {
    val key = taskKey(task.sessionId, task.file.fileId)
    return if (task.direction == TransferDirection.SEND) {
        sourceFiles[key]?.toString()
    } else {
        publishedFiles[key]?.toString()
    }
}

internal fun TransferEngine.persistTask(task: TransferTask, fileUri: String?) {
    scope.launch {
        runCatching { taskStore.upsert(TransferTaskStore.Entry(task = task, fileUri = fileUri)) }
            .onFailure { Log.w(TransferEngine.TAG, "任务历史写库失败: ${it.message}") }
    }
}

/**
 * 启动时加载历史（终态保留；崩溃残留的非终态按失败展示）。
 *
 * 非终态里「按同一超时口径已经卡死」的发送任务直接落 FAILED(4007)，
 * 否则每次重启都会在「进行中」看到同一批陈旧僵尸（真机 Bug：卡了两小时以上）。
 */
internal suspend fun TransferEngine.loadPersistedHistory() {
    val entries = runCatching { withContext(Dispatchers.IO) { taskStore.loadRecent() } }
        .onFailure { Log.w(TransferEngine.TAG, "任务历史读取失败: ${it.message}") }
        .getOrDefault(emptyList())
    if (entries.isEmpty()) return
    val now = System.currentTimeMillis()
    val restored = TransferHistoryRestore.restore(entries, now, confirmTimeoutMs, noProgressTimeoutMs)
    _tasks.update { current -> TransferHistoryRestore.merge(restored, current) }
    entries.forEach { entry ->
        val uri = entry.fileUri?.let { runCatching { it.toUri() }.getOrNull() } ?: return@forEach
        val key = taskKey(entry.task.sessionId, entry.task.file.fileId)
        // 按方向回填：发送任务落 sourceFiles（重发要用），接收任务落 publishedFiles（删除要用）。
        // 混着放会让「删除接收任务时删掉源文件」这类越界操作成为可能。
        if (entry.task.direction == TransferDirection.SEND) {
            sourceFiles[key] = uri
        } else {
            publishedFiles[key] = uri
        }
    }
}
