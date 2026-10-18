package com.bsfeng.monitor.domain.transfer

/**
 * 任务分区（纯 Kotlin，可单测）。
 *
 * 「进行中」只统计**仍未终结**的任务；失败 / 取消 / 拒绝 / 完成一律进「最近完成」，
 * 避免超时失败的任务继续挂在「进行中 · N」里（真机截图暴露的问题）。
 */
object TransferTaskGroups {

    /** 进行中：OFFERED / AWAITING_CONFIRM / TRANSFERRING。 */
    fun active(tasks: List<TransferTask>): List<TransferTask> = tasks.filter { it.isActive }

    /** 最近完成：终态（完成 / 失败 / 取消 / 拒绝），新任务在前。 */
    fun finished(tasks: List<TransferTask>): List<TransferTask> = tasks.filter { it.status.isTerminal }

    /** 「进行中 · N」的 N。 */
    fun activeCount(tasks: List<TransferTask>): Int = active(tasks).size

    /** 分组文案：没有进行中任务时返回「暂无进行中的传输」。 */
    fun activeTitle(tasks: List<TransferTask>): String {
        val count = activeCount(tasks)
        return if (count == 0) "进行中" else "进行中 · $count"
    }
}
