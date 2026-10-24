package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：接收侧的会话创建与协议响应构造。
// 与 TransferReceiveRoutes 的分工：routes 负责「校验请求并推进状态」，这里负责「造会话、造响应」。

import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.transport.ApiResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 接收侧辅助：会话创建与响应构造。 */
internal class TransferReceiveSupport(private val engine: TransferEngine) {

    fun createIncomingSession(
        senderDeviceId: String,
        senderAlias: String,
        files: List<TransferFile>,
    ): IncomingSession {
        val session = IncomingSession(
            sessionId = UUID.randomUUID().toString().replace("-", "").take(16),
            token = TransferSettings.generatePairingKey().take(32),
            senderDeviceId = senderDeviceId,
            senderAlias = senderAlias,
            files = files.associateTo(mutableMapOf()) { it.fileId to IncomingFile(it) },
            state = TransferStatus.OFFERED,
            createdAtMs = System.currentTimeMillis(),
        )
        engine.sessions[session.sessionId] = session
        files.forEach {
            engine.upsertTask(
                TransferTask(
                    sessionId = session.sessionId,
                    peer = PeerDevice(
                        deviceId = senderDeviceId,
                        alias = senderAlias,
                        deviceType = DeviceTypes.MAC,
                        hostAddress = "",
                        port = 0,
                    ),
                    direction = TransferDirection.RECV,
                    file = it,
                    status = TransferStatus.OFFERED,
                    updatedAtMs = System.currentTimeMillis(),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
        return session
    }

    fun approve(session: IncomingSession, persistedPairKey: String?): ApiResponse {
        // 续传：已存在的 `.part` 实际大小即已收偏移
        session.files.values.forEach { incoming ->
            incoming.received = engine.resumableOffset(incoming)
        }
        session.state = TransferStatus.TRANSFERRING
        session.pin = null
        session.lastProgressAtMs = System.currentTimeMillis()
        if (engine._pinPrompt.value?.sessionId == session.sessionId) engine._pinPrompt.value = null
        engine.refreshSessionTasks(session)
        return approvedResponse(session, persistedPairKey)
    }

    fun approvedResponse(session: IncomingSession, pairingKey: String?): ApiResponse =
        ApiResponse.ok(
            JSONObject().apply {
                put("sessionId", session.sessionId)
                put("token", session.token)
                put("state", TransferStatus.TRANSFERRING.wire)
                put("accepted", acceptedArray(session))
                if (pairingKey != null) put("pairingKey", pairingKey)
            },
        )

    fun pendingResponse(session: IncomingSession, message: String): ApiResponse =
        ApiResponse.ok(
            JSONObject().apply {
                put("sessionId", session.sessionId)
                put("token", session.token)
                put("state", TransferStatus.AWAITING_CONFIRM.wire)
                put("accepted", JSONArray())
                if (message.isNotBlank()) put("message", message)
            },
        )

    fun acceptedArray(session: IncomingSession): JSONArray = JSONArray().apply {
        session.files.values.forEach { incoming ->
            put(
                JSONObject().apply {
                    put("fileId", incoming.file.fileId)
                    // 与对端（Mac UploadPlanner.resumableOffset）同源：一律取 `.part` 实际大小
                    put("offset", engine.resumableOffset(incoming))
                },
            )
        }
    }

    /**
     * 速度估算：本次增量 / 时间差，再与上次值做 3:1 指数平滑（避免进度条数字乱跳）。
     *
     * 口径落在 [ProgressSpeed]（纯函数，可纯 JVM 单测）：进度被节流后，
     * 采样时间差随之变大，速度仍然是「真实字节差 / 真实时间差」，不会变 0 或跳变。
     */
}
