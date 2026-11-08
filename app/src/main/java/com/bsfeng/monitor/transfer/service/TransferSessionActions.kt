package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：会话级的用户操作（拒绝 / 关闭 PIN / 取消任务）。
// 这些都只改「会话状态 + 任务行」，不涉及 socket 生命周期。

import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 会话级用户操作。 */
internal class TransferSessionActions(private val engine: TransferEngine) {

    private val sessions get() = engine.sessions
    private val outgoing get() = engine.outgoing
    private val _pinPrompt get() = engine._pinPrompt
    private val _tasks get() = engine._tasks
    private val scope get() = engine.scope
    private val client get() = engine.client
    private val settings get() = engine.settings
    private val TokenHeader = TransferEngine.TokenHeader

    /** 接收端拒绝一次待确认的传输。 */
    fun denyIncoming(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.state = TransferStatus.DENIED
        session.pin = null
        session.files.values.forEach { it.part?.let { p -> if (p.exists()) p.delete() } }
        if (_pinPrompt.value?.sessionId == sessionId) _pinPrompt.value = null
        engine.refreshSessionTasks(session)
        engine.playSound(TransferSoundPlayer.Event.FAILURE)
    }

    /** 关闭 PIN 弹层（发送端取消本次发送）。 */
    fun dismissPin() {
        val pending = engine.pendingSend
        engine.pendingSend = null
        _pinPrompt.value = null
        if (pending != null) {
            _tasks.update { list ->
                list.map {
                    if (it.sessionId == pending.sessionId) {
                        it.copy(status = TransferStatus.CANCELLED, updatedAtMs = System.currentTimeMillis())
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** 取消一条任务（收：删 `.part`；发：尽力通知对端 `/cancel`）。 */
    fun cancelTask(sessionId: String) {
        sessions[sessionId]?.let { session ->
            session.state = TransferStatus.CANCELLED
            session.pin = null
            session.files.values.forEach { it.part?.let { p -> if (p.exists()) p.delete() } }
        }
        if (_pinPrompt.value?.sessionId == sessionId) _pinPrompt.value = null
        val outbound = outgoing.remove(sessionId)
        if (outbound != null) {
            scope.launch {
                runCatching {
                    client.postEmpty(
                        MiniHttpClient.buildUrl(
                            outbound.peer.baseUrl,
                            "cancel",
                            // 协议 §4：/cancel 要同时证明「拿的是本次会话的 token」与「是本次会话的发送方」
                            listOf(
                                "session" to outbound.remoteSessionId,
                                "sender" to settings.deviceId,
                            ),
                        ),
                        mapOf(TokenHeader to outbound.token),
                    )
                }
            }
        }
        _tasks.update { list ->
            list.map {
                if (it.sessionId == sessionId && it.isActive) {
                    it.copy(status = TransferStatus.CANCELLED, updatedAtMs = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
    }
}