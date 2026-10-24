package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：任务列表的写入与历史加载（TransferEngine 的扩展）。
// 落库口径：首次出现与进入终态各写一次，进度推进不写库。

import android.util.Log
import androidx.core.net.toUri
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
    val uri = publishedFiles[taskKey(updated.sessionId, updated.file.fileId)]?.toString()
    persistTask(updated, uri)
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
        val uri = entry.fileUri?.let { runCatching { it.toUri() }.getOrNull() }
        if (uri != null) publishedFiles[taskKey(entry.task.sessionId, entry.task.file.fileId)] = uri
    }
}
