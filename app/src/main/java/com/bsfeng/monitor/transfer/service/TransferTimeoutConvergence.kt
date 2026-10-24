package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：超时收敛（接收会话 + 发送任务，同一口径 4007）。
// 真机 Bug：「进行中」卡着一条发送任务两小时——发送会话没有超时路径，对端失联时永远不结束。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.transfer.policy.SendStallReason
import com.bsfeng.monitor.transfer.policy.SendTaskTimeoutPolicy

/** 超时收敛。 */
internal class TransferTimeoutConvergence(private val engine: TransferEngine) {

    fun convergeStalledSendTasks(now: Long) {
        engine._tasks.value
            .filter { it.direction == TransferDirection.SEND && !it.status.isTerminal }
            .forEach { task ->
                val reason = SendTaskTimeoutPolicy.reasonOf(
                    task = task,
                    nowMs = now,
                    confirmTimeoutMs = engine.confirmTimeoutMs,
                    noProgressTimeoutMs = engine.noProgressTimeoutMs,
                ) ?: return@forEach
                failSendSession(task.sessionId, reason)
            }
    }

    /**
     * 一条发送会话整体收敛（幂等）：同会话的活跃任务全部落 FAILED(4007)，
     * 摘掉 [outgoing] 条目（避免内存泄漏与「已收敛还在上传」）并收起等待输入的 PIN 弹层。
     */
    fun failSendSession(sessionId: String, reason: SendStallReason) {
        val targets = engine._tasks.value.filter { it.sessionId == sessionId && !it.status.isTerminal }
        // 先摘条目：即便任务行已被别处收敛，残留的 outgoing 条目也要清掉
        val tracked = engine.outgoing.remove(sessionId) != null
        if (targets.isEmpty()) return
        targets.forEach { engine.failTask(it.sessionId, it.file.fileId, TransferErrorCode.CONFIRM_TIMEOUT, reason.message) }
        if (engine.pendingSend?.sessionId == sessionId) {
            engine.pendingSend = null
            if (engine._pinPrompt.value?.sessionId == sessionId) engine._pinPrompt.value = null
        }
        if (tracked) engine.playSound(TransferSoundPlayer.Event.FAILURE)
        Log.i(
            TransferEngine.TAG,
            "发送会话 $sessionId 超时收敛：${reason.message}（${TransferErrorCode.CONFIRM_TIMEOUT}）",
        )
    }

    /** 会话失败收敛（幂等）：改状态 + 写任务行 + 清 PIN 弹层。 */
    fun failSession(session: IncomingSession, code: Int, message: String) {
        if (session.state.isTerminal) return
        session.state = TransferStatus.FAILED
        session.failureCode = code
        session.failureMessage = message
        session.pin = null
        // 先同步任务行再清 PIN 弹层：观察者看到弹层消失时任务状态已经是最终态
        engine.refreshSessionTasks(session)
        if (engine._pinPrompt.value?.sessionId == session.sessionId) engine._pinPrompt.value = null
        Log.i(TransferEngine.TAG, "会话 ${session.sessionId} 失败：${message.ifBlank { "确认超时" }}（$code）")
    }
}
