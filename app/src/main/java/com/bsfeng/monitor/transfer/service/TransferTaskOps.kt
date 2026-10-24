package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：任务行的批量操作与测试钩子
// （单条失败 / 整会话失败 / 改名 / 注入发送任务）。
// 测试钩子保持 internal，仅供单元测试使用。

import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import kotlinx.coroutines.flow.update

/** 任务批量操作。 */
internal class TransferTaskOps(private val engine: TransferEngine) {

    fun enforceTimeoutsForTesting() {
        engine.enforceTimeouts()
    }

    /**
     * 仅供测试：注入一条发送任务行，并按需登记对应的 [outgoing] 条目
     * （用于验证「超时收敛 → 任务落 FAILED 且条目被摘除」）。
     */
    fun injectSendTaskForTesting(task: TransferTask, trackOutgoing: Boolean = true) {
        engine.upsertTask(task)
        if (trackOutgoing) {
            engine.outgoing[task.sessionId] = OutgoingSession(
                peer = task.peer,
                remoteSessionId = "remote-${task.sessionId}",
                token = "test-token",
                sources = emptyList(),
            )
        }
    }

    /** 仅供测试：当前登记中的发送会话条目数。 */
    internal fun outgoingSessionCountForTesting(): Int = engine.outgoing.size

    fun renameTasks(fromSessionId: String, toSessionId: String) {
        engine._tasks.update { current ->
            current.map {
                if (it.sessionId == fromSessionId) it.copy(sessionId = toSessionId) else it
            }
        }
    }

    fun failTask(sessionId: String, fileId: String, code: Int, message: String) {
        engine.patchTask(sessionId, fileId) {
            it.copy(
                status = TransferStatus.FAILED,
                errorCode = code,
                message = message,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun failAll(sessionId: String, code: Int, message: String) {
        var failedAny = false
        engine._tasks.update { current ->
            current.map {
                if (it.sessionId == sessionId && it.isActive) {
                    failedAny = true
                    it.copy(
                        status = TransferStatus.FAILED,
                        errorCode = code,
                        message = message,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            }
        }
        if (failedAny) engine.playSound(TransferSoundPlayer.Event.FAILURE)
    }
}
