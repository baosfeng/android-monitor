package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：仅供单元测试使用的注入与观测入口。
// 生产路径不调用这些方法，拆开是为了让引擎主文件只留运行时逻辑。

import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.service.TransferEngine.Companion.TAG

/**
 * 仅供测试：立即跑一次超时收敛（生产由 [tickerLoop] 每 tick 调用、`offerStatus` 顺带触发）。
 */
internal fun TransferEngine.enforceTimeoutsForTesting() {
    enforceTimeouts()
}

/**
 * 仅供测试：注入一条发送任务行，并按需登记对应的 [outgoing] 条目
 * （用于验证「超时收敛 → 任务落 FAILED 且条目被摘除」）。
 */
internal fun TransferEngine.injectSendTaskForTesting(task: TransferTask, trackOutgoing: Boolean = true) {
    upsertTask(task)
    if (trackOutgoing) {
        outgoing[task.sessionId] = OutgoingSession(
            peer = task.peer,
            remoteSessionId = "remote-${task.sessionId}",
            token = "test-token",
            sources = emptyList(),
        )
    }
}

/** 仅供测试：当前登记中的发送会话条目数。 */
internal fun TransferEngine.outgoingSessionCountForTesting(): Int = outgoing.size

