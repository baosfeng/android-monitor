package com.bsfeng.monitor.domain.transfer

/**
 * 任务删除与撤销（纯 Kotlin，可单测）。
 *
 * 规则（UI「滑动删除」的唯一口径）：
 * - 只有**已终结**任务可删（完成 / 失败 / 取消 / 对方拒绝），进行中的任务返回 `null`，调用方保持原列表；
 * - 删除记录时一并清理**已落盘的接收文件**；发送任务的文件属于用户本机源文件，绝不删除；
 * - 失败 / 取消 / 无落盘文件的接收任务只移除记录（`.part` 续传数据按协议 §6 保留）；
 * - 删除快照在撤销窗口内保留，[restore] 按原下标放回；接收文件已删时由调用方提示「仅恢复记录」。
 *
 * 任务列表当前是**进程内内存态**（协议没有任务持久化要求）：删除/撤销只保证进程内一致，
 * 进程被回收后列表与撤销窗口一起消失。
 */
object TransferTaskDeletion {

    /** 是否允许滑动删除（终态任务）。 */
    fun isDeletable(task: TransferTask): Boolean = task.status.isTerminal

    /**
     * 生成删除快照；任务不存在或仍在进行中返回 `null`（不改列表、不删文件）。
     *
     * @param removeReceivedFile 仅对「接收 + 已完成」调用：返回 true 表示该任务确有已落盘接收文件被清理。
     * @param deletionId 撤销窗口的标识（由调用方生成，便于测试与去重）。
     */
    fun delete(
        tasks: List<TransferTask>,
        sessionId: String,
        fileId: String,
        deletionId: String,
        removeReceivedFile: (TransferTask) -> Boolean,
    ): DeletedTask? {
        val index = tasks.indexOfFirst { it.sessionId == sessionId && it.file.fileId == fileId }
        if (index < 0) return null
        val task = tasks[index]
        if (!isDeletable(task)) return null
        val fileDeleted = task.direction == TransferDirection.RECV &&
            task.status == TransferStatus.COMPLETED &&
            removeReceivedFile(task)
        return DeletedTask(
            deletionId = deletionId,
            task = task,
            index = index,
            fileDeleted = fileDeleted,
        )
    }

    /** 从列表中移除快照对应的任务（同 sessionId + fileId）。 */
    fun without(tasks: List<TransferTask>, deleted: DeletedTask): List<TransferTask> =
        tasks.filterNot { sameTask(it, deleted.task) }

    /** 撤销删除：按原下标放回；若同一条任务已存在则不重复插入。 */
    fun restore(tasks: List<TransferTask>, deleted: DeletedTask): List<TransferTask> {
        if (tasks.any { sameTask(it, deleted.task) }) return tasks
        val index = deleted.index.coerceIn(0, tasks.size)
        return tasks.toMutableList().also { it.add(index, deleted.task) }
    }

    private fun sameTask(a: TransferTask, b: TransferTask): Boolean =
        a.sessionId == b.sessionId && a.file.fileId == b.file.fileId
}

/**
 * 删除快照（撤销窗口内保留在内存）。
 *
 * [fileDeleted] 为 true 表示该任务的已落盘接收文件随记录一起被删除，撤销只能恢复记录。
 */
data class DeletedTask(
    val deletionId: String,
    val task: TransferTask,
    val index: Int,
    val fileDeleted: Boolean,
)
