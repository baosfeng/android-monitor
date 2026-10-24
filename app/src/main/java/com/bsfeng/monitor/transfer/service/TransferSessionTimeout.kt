package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：接收会话的超时判定规则。
// 纯判定（不改进程状态）：引擎拿到结论后自己去做收敛动作。

import com.bsfeng.monitor.domain.transfer.TransferStatus

/** 接收会话超时判定。 */
internal object TransferSessionTimeout {

    /**
     * 会话是否已经该收敛。
     *
     * - 待确认超过 `confirmDeadlineMs` → [Reason.CONFIRM_TIMEOUT]（协议 4007）；
     * - 传输中连续 `noProgressTimeoutMs` 没有字节进展 → [Reason.NO_PROGRESS]（同为 4007）。
     */
    fun reasonOf(
        state: TransferStatus,
        confirmDeadlineMs: Long,
        lastProgressAtMs: Long,
        nowMs: Long,
        noProgressTimeoutMs: Long,
    ): Reason? = when {
        state == TransferStatus.AWAITING_CONFIRM && nowMs >= confirmDeadlineMs -> Reason.CONFIRM_TIMEOUT

        state == TransferStatus.TRANSFERRING && nowMs - lastProgressAtMs >= noProgressTimeoutMs ->
            Reason.NO_PROGRESS

        else -> null
    }

    /** 收敛原因；文案与既有约定一致（确认超时保持空串，走任务行默认文案）。 */
    enum class Reason(val message: String) {
        CONFIRM_TIMEOUT(""),
        NO_PROGRESS("长时间无进展"),
    }
}
