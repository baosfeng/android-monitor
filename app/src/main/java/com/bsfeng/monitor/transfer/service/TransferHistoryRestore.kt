package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：启动时把库里的任务历史还原成内存列表。
// 只做数据转换（终态保留 / 崩溃残留按超时口径判失败），不碰 StateFlow。

import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import com.bsfeng.monitor.transfer.policy.SendTaskTimeoutPolicy

/** 历史任务的还原规则。 */
internal object TransferHistoryRestore {

    /**
     * 把库里的历史条目转成可展示的任务行。
     *
     * 终态原样保留；非终态里「按同一超时口径已经卡死」的直接落 FAILED(4007)，
     * 否则每次重启都会在「进行中」看到同一批陈旧僵尸（真机 Bug：卡了两小时以上）。
     */
    fun restore(
        entries: List<TransferTaskStore.Entry>,
        nowMs: Long,
        confirmTimeoutMs: Long,
        noProgressTimeoutMs: Long,
    ): List<TransferTask> = entries.map { entry ->
        val task = entry.task
        if (task.status.isTerminal) {
            task
        } else {
            val reason = SendTaskTimeoutPolicy.reasonOf(
                task = task,
                nowMs = nowMs,
                confirmTimeoutMs = confirmTimeoutMs,
                noProgressTimeoutMs = noProgressTimeoutMs,
            )
            task.copy(
                status = TransferStatus.FAILED,
                errorCode = if (reason != null) {
                    TransferErrorCode.CONFIRM_TIMEOUT
                } else {
                    TransferErrorCode.SESSION_EXPIRED
                },
                message = reason?.message ?: "上次未完成",
                updatedAtMs = nowMs,
            )
        }
    }

    /** 还原后的列表放前面，已有的（本次进程新建的）保持在后。 */
    fun merge(restored: List<TransferTask>, current: List<TransferTask>): List<TransferTask> {
        val existing = current.map { it.sessionId to it.file.fileId }.toSet()
        return restored.filterNot { (it.sessionId to it.file.fileId) in existing } + current
    }
}
