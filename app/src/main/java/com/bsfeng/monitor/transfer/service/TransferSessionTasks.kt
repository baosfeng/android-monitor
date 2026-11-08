package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：会话与任务行的同步、确认超时、过期会话清理。
// 时序要点：先落任务终态、再收起 PIN 弹层，避免「弹层没了、卡片还在等待确认」。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 会话任务同步。 */
internal class TransferSessionTasks(private val engine: TransferEngine) {

    fun scheduleConfirmTimeout(session: IncomingSession) {
        engine.scope.launch {
            while (session.state == TransferStatus.AWAITING_CONFIRM &&
                System.currentTimeMillis() < session.confirmDeadlineMs
            ) {
                delay((session.confirmDeadlineMs - System.currentTimeMillis()).coerceAtLeast(1L))
            }
            if (session.state == TransferStatus.AWAITING_CONFIRM &&
                System.currentTimeMillis() >= session.confirmDeadlineMs
            ) {
                session.state = TransferStatus.FAILED
                session.pin = null
                // 先落任务终态、再收起弹层：外部（UI / 测试）看到弹层消失时任务一定已经不在「进行中」，
                // 不会出现「弹层没了、卡片还显示等待确认」的中间态。
                // 超时只静默收起 PIN 弹层：不播失败音、不震（避免打扰）
                refreshSessionTasks(session, notifyFailure = false)
                if (engine._pinPrompt.value?.sessionId == session.sessionId) engine._pinPrompt.value = null
                Log.i(TransferEngine.TAG, "会话 ${session.sessionId} 确认超时（${engine.confirmTimeoutMs}ms）")
            }
        }
    }

    fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = engine.sessions.values.filter {
            it.state.isTerminal && now - it.createdAtMs > TransferEngine.SESSION_KEEP_MS
        }
        expired.forEach { engine.sessions.remove(it.sessionId) }
    }

    fun refreshSessionTasks(session: IncomingSession, notifyFailure: Boolean = true) {
        session.files.values.forEach { incoming ->
            engine.patchTask(session.sessionId, incoming.file.fileId) {
                it.copy(
                    status = session.state,
                    receivedBytes = incoming.received,
                    errorCode = if (session.state == TransferStatus.FAILED) {
                        session.failureCode ?: TransferErrorCode.CONFIRM_TIMEOUT
                    } else {
                        null
                    },
                    message = if (session.state == TransferStatus.FAILED) {
                        session.failureMessage.ifBlank { session.state.wire }
                    } else {
                        session.state.wire
                    },
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (notifyFailure &&
            (session.state == TransferStatus.FAILED || session.state == TransferStatus.DENIED)
        ) {
            engine.playSound(TransferSoundPlayer.Event.FAILURE)
        }
    }
}
