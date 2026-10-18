package com.bsfeng.monitor.domain.transfer

/**
 * 会话状态机（协议 §4 / §4.1）。
 *
 * 设计约束（可单测的纯 Kotlin）：
 * - **非法迁移不改状态**：[transition] 返回 [TransferTransition.Rejected]，[status] / [receivedBytes] 保持原值；
 * - **进度单调**：`Progress(received)` 只接受 `receivedBytes <= received <= totalBytes`；
 * - **完成必须收满**：总量已知时 `Complete` 要求 `receivedBytes == totalBytes`；
 * - **终态只能 Reset**：`COMPLETED / FAILED / CANCELLED / DENIED` 只接受 [TransferEvent.Reset]。
 */
class TransferSessionMachine(
    val sessionId: String,
    val totalBytes: Long,
    initialStatus: TransferStatus = TransferStatus.IDLE,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        require(totalBytes >= 0L) { "totalBytes 不能为负：$totalBytes" }
    }

    var status: TransferStatus = initialStatus
        private set

    /** 已接收 / 已发送字节数（单调不减）。 */
    var receivedBytes: Long = 0L
        private set

    /** 最近一次失败的错误码（仅 [TransferStatus.FAILED] 时有意义）。 */
    var errorCode: Int? = null
        private set

    /** 迁移历史（首项为初始状态），供 UI / 日志回看。 */
    private val trail = mutableListOf(initialStatus)

    /** 状态迁移轨迹（只读快照）。 */
    fun history(): List<TransferStatus> = trail.toList()

    /**
     * 执行一次迁移。
     *
     * @return [TransferTransition.Applied] 表示已落库；[TransferTransition.Rejected] 表示非法迁移，
     *   此时 [status] / [receivedBytes] / [errorCode] 均不变。
     */
    fun transition(event: TransferEvent): TransferTransition {
        val from = status
        when (event) {
            is TransferEvent.Offer -> return when (from) {
                TransferStatus.IDLE -> apply(from, TransferStatus.OFFERED, event)
                else -> reject(from, event, "只有 IDLE 能提交清单，当前 ${from.wire}")
            }

            is TransferEvent.Pending -> return when (from) {
                TransferStatus.OFFERED -> apply(from, TransferStatus.AWAITING_CONFIRM, event)
                else -> reject(from, event, "只有 OFFERED 能进入待确认，当前 ${from.wire}")
            }

            is TransferEvent.Approve -> return when (from) {
                TransferStatus.OFFERED, TransferStatus.AWAITING_CONFIRM -> {
                    if (event.offset != receivedBytes) {
                        reject(
                            from,
                            event,
                            "续传偏移不匹配：对方 offset=${event.offset}，本端已收 ${receivedBytes}",
                        )
                    } else {
                        apply(from, TransferStatus.TRANSFERRING, event)
                    }
                }
                else -> reject(from, event, "只有 OFFERED / 待确认 能通过确认，当前 ${from.wire}")
            }

            is TransferEvent.Progress -> return when (from) {
                TransferStatus.TRANSFERRING -> when {
                    event.received < receivedBytes ->
                        reject(from, event, "进度回退：${event.received} < ${receivedBytes}")
                    totalBytes > 0L && event.received > totalBytes ->
                        reject(from, event, "进度越界：${event.received} > ${totalBytes}")
                    else -> {
                        receivedBytes = event.received
                        TransferTransition.Applied(from, from, event)
                    }
                }
                else -> reject(from, event, "只有 TRANSFERRING 能推进度，当前 ${from.wire}")
            }

            is TransferEvent.Complete -> return when (from) {
                TransferStatus.TRANSFERRING -> {
                    if (totalBytes > 0L && receivedBytes != totalBytes) {
                        reject(from, event, "未收满：${receivedBytes}/${totalBytes}")
                    } else {
                        apply(from, TransferStatus.COMPLETED, event)
                    }
                }
                else -> reject(from, event, "只有 TRANSFERRING 能完成，当前 ${from.wire}")
            }

            is TransferEvent.Fail -> return when {
                from.isTerminal -> reject(from, event, "终态 ${from.wire} 不能再失败")
                else -> {
                    errorCode = event.code
                    apply(from, TransferStatus.FAILED, event)
                }
            }

            is TransferEvent.Deny -> return when (from) {
                TransferStatus.OFFERED, TransferStatus.AWAITING_CONFIRM -> {
                    errorCode = TransferErrorCode.USER_DENIED
                    apply(from, TransferStatus.DENIED, event)
                }
                else -> reject(from, event, "只有 OFFERED / 待确认 能被拒绝，当前 ${from.wire}")
            }

            is TransferEvent.Cancel -> return when {
                from.isTerminal -> reject(from, event, "终态 ${from.wire} 不能取消")
                from == TransferStatus.IDLE -> reject(from, event, "IDLE 没有可取消的会话")
                else -> apply(from, TransferStatus.CANCELLED, event)
            }

            is TransferEvent.Reset -> return when {
                from.isTerminal -> {
                    receivedBytes = 0L
                    errorCode = null
                    apply(from, TransferStatus.IDLE, event)
                }
                else -> reject(from, event, "只有终态能重置，当前 ${from.wire}")
            }
        }
    }

    private fun apply(
        from: TransferStatus,
        to: TransferStatus,
        event: TransferEvent,
    ): TransferTransition {
        status = to
        trail += to
        return TransferTransition.Applied(from, to, event)
    }

    private fun reject(
        from: TransferStatus,
        event: TransferEvent,
        reason: String,
    ): TransferTransition = TransferTransition.Rejected(from, event, reason)
}

/** 状态机事件。 */
sealed class TransferEvent {
    /** 清单已提交（`POST /offer` 成功）。 */
    data object Offer : TransferEvent()

    /** 未配对，接收端已弹 PIN（响应 `state=pending`）。 */
    data object Pending : TransferEvent()

    /** 确认通过；[offset] 是接收端声明的已收字节（新文件为 0）。 */
    data class Approve(val offset: Long = 0L) : TransferEvent()

    /** 字节进展。 */
    data class Progress(val received: Long) : TransferEvent()

    /** 收满且 sha256 校验通过。 */
    data object Complete : TransferEvent()

    /** 失败（协议 §4.1 错误码）。 */
    data class Fail(val code: Int = TransferErrorCode.SESSION_EXPIRED) : TransferEvent()

    /** 对方拒绝。 */
    data object Deny : TransferEvent()

    /** 本端取消。 */
    data object Cancel : TransferEvent()

    /** 终态复位（UI 重新开始）。 */
    data object Reset : TransferEvent()
}

/** 迁移结果。 */
sealed class TransferTransition {
    /** 迁移已生效（[from] == [to] 表示原地推进度）。 */
    data class Applied(
        val from: TransferStatus,
        val to: TransferStatus,
        val event: TransferEvent,
    ) : TransferTransition()

    /** 非法迁移，状态未变。 */
    data class Rejected(
        val from: TransferStatus,
        val event: TransferEvent,
        val reason: String,
    ) : TransferTransition()
}
