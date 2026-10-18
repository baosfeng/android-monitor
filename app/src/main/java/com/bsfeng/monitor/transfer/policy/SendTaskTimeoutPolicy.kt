package com.bsfeng.monitor.transfer.policy

import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask

/**
 * 发送任务卡死原因（纯 Kotlin，可纯 JVM 单测）。
 *
 * 文案直接进任务行，用户能看懂「为什么失败」。
 */
enum class SendStallReason(val message: String) {
    /** 已提交清单，但对端迟迟没有回 approved / pending：协议 §4.1 确认超时。 */
    NOT_ACKNOWLEDGED("对端未确认"),

    /** 已开始上传，但长时间没有字节进展（对端掉线 / 网络静默）。 */
    NO_PROGRESS("长时间无进展"),
}

/**
 * 发送侧超时判定（纯逻辑，无 Android 依赖，可纯 JVM 单测）。
 *
 * 与接收侧 `TransferEngine.enforceTimeouts()` 同一口径、同一错误码（4007）：
 * - `OFFERED`（本端已提交清单，等对端确认）用 `createdAtMs` 对比 [confirmTimeoutMs]（默认 60s）；
 * - `TRANSFERRING`（已在上传）用 `updatedAtMs`（进度推进时刷新）对比 [noProgressTimeoutMs]；
 * - 只有 `SEND` 方向参与判定，接收侧沿用原有「会话超时」路径；
 * - 终态、时间戳缺失（≤0）一律不收敛，避免历史脏数据被误杀。
 */
object SendTaskTimeoutPolicy {

    /**
     * 判定一条任务是否已经卡死。
     *
     * @return 卡死原因；`null` 表示不收敛（未超时 / 不该由本策略处理）。
     */
    fun reasonOf(
        task: TransferTask,
        nowMs: Long,
        confirmTimeoutMs: Long,
        noProgressTimeoutMs: Long,
    ): SendStallReason? {
        if (task.direction != TransferDirection.SEND) return null
        if (task.status.isTerminal) return null
        return when (task.status) {
            TransferStatus.OFFERED -> {
                val anchor = firstPositive(task.createdAtMs, task.updatedAtMs)
                if (anchor != null && nowMs - anchor >= confirmTimeoutMs) {
                    SendStallReason.NOT_ACKNOWLEDGED
                } else {
                    null
                }
            }

            TransferStatus.TRANSFERRING -> {
                val anchor = firstPositive(task.updatedAtMs, task.createdAtMs)
                if (anchor != null && nowMs - anchor >= noProgressTimeoutMs) {
                    SendStallReason.NO_PROGRESS
                } else {
                    null
                }
            }

            else -> null
        }
    }

    /** 是否已卡死（[reasonOf] 的布尔快捷方式）。 */
    fun isStalled(
        task: TransferTask,
        nowMs: Long,
        confirmTimeoutMs: Long,
        noProgressTimeoutMs: Long,
    ): Boolean = reasonOf(task, nowMs, confirmTimeoutMs, noProgressTimeoutMs) != null

    /** 距离判超时还剩多少毫秒（已超时 / 不参与判定返回 0）。 */
    fun remainingMs(
        task: TransferTask,
        nowMs: Long,
        confirmTimeoutMs: Long,
        noProgressTimeoutMs: Long,
    ): Long {
        if (task.direction != TransferDirection.SEND || task.status.isTerminal) return 0L
        val stamp: Long
        val timeout: Long
        when (task.status) {
            TransferStatus.OFFERED -> {
                stamp = firstPositive(task.createdAtMs, task.updatedAtMs) ?: return 0L
                timeout = confirmTimeoutMs
            }

            TransferStatus.TRANSFERRING -> {
                stamp = firstPositive(task.updatedAtMs, task.createdAtMs) ?: return 0L
                timeout = noProgressTimeoutMs
            }

            else -> return 0L
        }
        return (stamp + timeout - nowMs).coerceAtLeast(0L)
    }

    private fun firstPositive(vararg values: Long): Long? =
        values.firstOrNull { it > 0L }
}
