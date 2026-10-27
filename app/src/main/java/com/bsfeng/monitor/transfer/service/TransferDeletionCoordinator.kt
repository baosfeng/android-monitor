package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：任务删除与撤销窗口。
// 撤销窗口的意义：5 秒内点「撤销」能把记录放回原下标；落盘文件一旦被删就不再恢复。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.TransferTaskDeletion
import java.util.UUID
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 删除 / 撤销编排。 */
internal class TransferDeletionCoordinator(private val engine: TransferEngine) {


    /**
     * 删除一条已终结任务（记录 + 已落盘接收文件），完成后经 [deletedTasks] 通知 UI 展示撤销窗口。
     *
     * 进行中任务在 [deleteTaskNow] 处被拒绝（UI 侧滑动回弹），不会产生事件。
     */
    fun deleteTask(sessionId: String, fileId: String) {
        engine.scope.launch {
            deleteTaskNow(sessionId, fileId)?.let { engine._deletedTasks.emit(it) }
        }
    }

    /**
     * 删除的同步实现（IO 线程调用；测试可直接调用不走 Flow）。
     *
     * @return 删除快照；任务不存在或仍在进行中时返回 `null`，列表保持不变。
     */
    internal fun deleteTaskNow(sessionId: String, fileId: String): DeletedTask? {
        val key = engine.taskKey(sessionId, fileId)
        val deleted = TransferTaskDeletion.delete(
            tasks = engine._tasks.value,
            sessionId = sessionId,
            fileId = fileId,
            deletionId = UUID.randomUUID().toString(),
            removeReceivedFile = {
                val uri = engine.publishedFiles.remove(key) ?: return@delete false
                engine.downloads.delete(uri)
                // 只要登记过落盘产物，撤销时就无法把文件恢复到 Downloads（可能已被系统清理）
                true
            },
        ) ?: return null
        engine._tasks.update { TransferTaskDeletion.without(it, deleted) }
        // 发送任务的源 Uri 是**引用**（用户自己的文件），删记录不删文件 → 这里**不清**登记：
        // 撤销删除要按原 Uri 回写记录，清掉就再也补不回来。行没了自然也没有重发入口。
        engine.deletedSnapshots[deleted.deletionId] = deleted
        engine.scope.launch {
            runCatching {
                engine.taskStore.delete(deleted.task.sessionId, deleted.task.file.fileId)
            }.onFailure { Log.w(TransferEngine.TAG, "任务历史删除失败: ${it.message}") }
        }
        return deleted
    }

    /**
     * 撤销删除：把任务按原下标放回列表。
     *
     * @return 删除快照；撤销窗口已结束 / 已撤销过时返回 `null`。
     *   快照的 [DeletedTask.fileDeleted] 为 true 时，UI 另提示「文件已删除，仅恢复记录」。
     */
    fun undoDelete(deletionId: String): DeletedTask? {
        val deleted = engine.deletedSnapshots.remove(deletionId) ?: return null
        engine._tasks.update { TransferTaskDeletion.restore(it, deleted) }
        // 按方向回写：发送任务落 sourceFiles（重发要用），接收任务落 publishedFiles
        // （文件已删时这里写回的是旧路径，persistTask 只存记录，不会把文件变回来）
        engine.persistTask(deleted.task, engine.uriForPersist(deleted.task))
        return deleted
    }

    /** 撤销窗口结束：丢弃快照，记录不再恢复。 */
    fun commitDelete(deletionId: String) {
        engine.deletedSnapshots.remove(deletionId)
    }
}
