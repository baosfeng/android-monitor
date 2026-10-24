package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：删除 / 撤销的对外入口。
// 具体实现仍在 TransferDeletionCoordinator；这里只做任务行 + 事件广播的衔接。

import com.bsfeng.monitor.domain.transfer.DeletedTask
import kotlinx.coroutines.launch

/**
 * 删除一条已终结任务（记录 + 已落盘接收文件），完成后经 [deletedTasks] 通知 UI 展示撤销窗口。
 */
internal fun TransferEngine.deleteTaskImpl(sessionId: String, fileId: String) {
    scope.launch {
        deleteTaskNow(sessionId, fileId)?.let { _deletedTasks.emit(it) }
    }
}

/** 删除的同步实现（IO 线程调用；测试可直接调用不走 Flow）。 */
internal fun TransferEngine.deleteTaskNow(sessionId: String, fileId: String): DeletedTask? =
    deletion.deleteTaskNow(sessionId, fileId)

/** 撤销删除：把任务按原下标放回列表。 */
internal fun TransferEngine.undoDeleteImpl(deletionId: String): DeletedTask? = deletion.undoDelete(deletionId)

/** 撤销窗口结束：丢弃快照，记录不再恢复。 */
internal fun TransferEngine.commitDeleteImpl(deletionId: String) = deletion.commitDelete(deletionId)
