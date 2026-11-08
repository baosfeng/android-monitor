package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：接收侧协议路由（协议 §4）。
// 只做「请求校验 → 会话状态推进 → 响应构造」，网络与字节流落在 MiniHttpServer / DownloadsSink。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.transfer.transport.ApiResponse
import com.bsfeng.monitor.transfer.transport.StreamCopier
import com.bsfeng.monitor.transfer.policy.PendingSessionBudget
import com.bsfeng.monitor.transfer.policy.ProgressThrottle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** 接收侧路由（`GET /info`、`POST /offer`、`GET /offer/status`、`PUT /upload`、`POST /cancel`）。 */
internal class TransferReceiveRoutes(private val engine: TransferEngine) {

    private companion object {
        /**
         * 同一会话内允许输错 PIN 的次数上限。
         *
         * 4 位 PIN 只有 10^4 种，局域网内往返 <5ms、确认窗口 60s，不限次数等于没有 PIN；
         * 5 次后作废会话，正常用户一次就能输对，误触也够用。
         */
        const val MAX_PIN_FAILURES = 5
    }

    suspend fun info(): ApiResponse = ApiResponse.ok(
        JSONObject().apply {
            put("deviceId", engine.settings.deviceId)
            put("alias", engine.settings.alias)
            put("deviceType", DeviceTypes.ANDROID)
            put("port", engine.localPort)
            put("proto", "http")
            put("caps", JSONArray(listOf("send", "recv")))
            put("appVersion", engine.appVersion())
        },
    )

    suspend fun offer(body: JSONObject, pairKey: String?, source: String): ApiResponse {
        val senderDeviceId = body.optString("senderDeviceId", "")
        if (senderDeviceId.isBlank()) {
            return ApiResponse.error(ApiResponse.PROTOCOL_ERROR, "缺少 senderDeviceId")
        }
        // 别名优先取 offer 自身的 senderAlias，发现层表仅作兜底；两者都空才回落「未知设备」
        // （发现层在 alias 为空时会用 deviceId 占位，这种占位不算真实别名）
        val senderAlias = body.optString("senderAlias", "").trim()
            .ifBlank {
                engine.registry.find(senderDeviceId)?.alias?.trim()
                    ?.takeIf { it.isNotBlank() && it != senderDeviceId }
                    .orEmpty()
            }
            .ifBlank { UNKNOWN_PEER_ALIAS }
        val filesJson = body.optJSONArray("files") ?: return ApiResponse.error(
            ApiResponse.PROTOCOL_ERROR,
            "缺少 files",
        )
        val files = TransferWireCodec.parseFiles(filesJson) ?: return ApiResponse.error(
            ApiResponse.PROTOCOL_ERROR,
            "files 字段非法",
        )
        if (files.isEmpty()) return ApiResponse.error(ApiResponse.PROTOCOL_ERROR, "files 为空")
        val totalBytes = files.sumOf { it.size }
        if (engine.freeBytes() < totalBytes + TransferEngine.DISK_MARGIN_BYTES) {
            return ApiResponse.error(TransferErrorCode.DISK_FULL)
        }
        // 暂停接收（通知上的「暂停 5 分钟」）：不建会话、不弹 PIN，直接回 denied
        if (engine.settings.isOffersPaused()) {
            return ApiResponse.ok(
                JSONObject().apply {
                    put("sessionId", "")
                    put("token", "")
                    put("state", TransferStatus.DENIED.wire)
                    put("accepted", JSONArray())
                    put("message", "接收端已暂停")
                },
            )
        }

        val requestedSessionId = body.optString("sessionId", "").takeIf { it.isNotBlank() }
        val reusable = requestedSessionId?.let { engine.sessions[it] }?.takeIf { it.senderDeviceId == senderDeviceId }
            ?: engine.sessions.values.firstOrNull {
                it.senderDeviceId == senderDeviceId &&
                    it.state != TransferStatus.COMPLETED && it.state != TransferStatus.CANCELLED &&
                    it.files.values.map { f -> f.file.sha256 }.sorted() == files.map { it.sha256 }.sorted()
            }
        // 入站限流（SEC-04）：只对**新建会话**计数。
        //
        // 为什么只算新建：限流的靶子是「50 条并发 offer 把 sessions 表与 PIN 弹层打爆」，
        // 而 PIN 重输、断线重提都是复用同一条会话（见上面 reusable 的查找）。把它们也算进
        // 冷却窗，正常用户输错两次 PIN 就会被自己的限流挡住。
        //
        // 错误码复用既有 4006（用户拒绝）：协议 §4.1 里最接近的语义是「接收端不收这次传输」，
        // 新造码会让发送端只显示「传输失败（code）」，读不出下一步该干什么。
        if (reusable == null) {
            if (!engine.offerLimiter.allow(source, System.currentTimeMillis())) {
                Log.w(TransferEngine.TAG, "offer 超出每来源冷却窗，已拒绝：source=$source")
                return ApiResponse.error(TransferErrorCode.USER_DENIED, "请求过于频繁，请稍后重试")
            }
            if (PendingSessionBudget.shouldReject(engine.pendingSessionCount())) {
                Log.w(TransferEngine.TAG, "未确认会话已达上限，已拒绝：source=$source")
                return ApiResponse.error(TransferErrorCode.USER_DENIED, "待确认的接收任务过多，请稍后重试")
            }
        }
        val session = reusable ?: engine.createIncomingSession(senderDeviceId, senderAlias, files)

        // 会话已批准（重复 offer 幂等返回）
        if (session.state == TransferStatus.TRANSFERRING) {
            return engine.approvedResponse(session, null)
        }

        val pin = body.optString("pin", "").takeIf { it.isNotBlank() }
        val storedPairKey = engine.settings.pairingKey(senderDeviceId)
        val pairKeyOk = !pairKey.isNullOrBlank() && storedPairKey != null && pairKey == storedPairKey

        if (pairKeyOk) {
            return engine.approve(session, persistedPairKey = null)
        }
        // 免配对窗口 / 体积阈值（OR）：命中即免 PIN，顺带建立配对便于后续免确认
        if (engine.settings.shouldAutoApprove(totalBytes)) {
            if (engine._pinPrompt.value?.sessionId == session.sessionId) engine._pinPrompt.value = null
            val newKey = TransferSettings.generatePairingKey()
            engine.settings.savePairingKey(senderDeviceId, newKey)
            return engine.approve(session, persistedPairKey = newKey)
        }
        if (pin != null) {
            if (session.state != TransferStatus.AWAITING_CONFIRM) {
                // 没有待确认会话时收到 PIN：复位为待确认再校验，避免时序竞态
                session.state = TransferStatus.AWAITING_CONFIRM
            }
            if (session.pin != null && session.pin == pin) {
                val newKey = TransferSettings.generatePairingKey()
                engine.settings.savePairingKey(senderDeviceId, newKey)
                return engine.approve(session, persistedPairKey = newKey)
            }
            // 4 位 PIN 空间只有 10^4，且窗口内可无限重试 → 必须按会话计数封顶，
            // 否则局域网内任意主机都能在 60 秒里枚举完并把自己写成可信设备。
            session.pinFailures += 1
            if (session.pinFailures >= MAX_PIN_FAILURES) {
                engine.failSession(session, TransferErrorCode.USER_DENIED, "PIN 错误次数过多，本次传输已取消")
                return ApiResponse.error(TransferErrorCode.USER_DENIED, "PIN 错误次数过多，本次传输已取消")
            }
            return engine.pendingResponse(session, "PIN 不正确")
        }

        // 未配对且没有 PIN：生成 PIN 并等待（协议 §4.1：60s 超时 → 4007）
        if (session.pin == null || System.currentTimeMillis() > session.confirmDeadlineMs) {
            session.pin = TransferSettings.generatePin()
            session.confirmDeadlineMs = System.currentTimeMillis() + engine.confirmTimeoutMs
            engine.scheduleConfirmTimeout(session)
        }
        session.state = TransferStatus.AWAITING_CONFIRM
        engine._pinPrompt.value = TransferEngine.PinPrompt(
            sessionId = session.sessionId,
            peerAlias = senderAlias,
            direction = TransferDirection.RECV,
            pin = session.pin,
            fileName = files.first().name,
            expiresAtMs = session.confirmDeadlineMs,
        )
        engine.refreshSessionTasks(session)
        return engine.pendingResponse(session, "")
    }

    /**
     * `GET /offer/status`：查询会话确认状态。
     *
     * 必须带 `X-Transfer-Token`（与 [upload] 同口径）：会话 id 只有 16 位十六进制，
     * 明文 HTTP 下同网段嗅到就能读进度、进而用 [cancel] 中断他人传输。
     */
    suspend fun offerStatus(sessionId: String, token: String?): ApiResponse {
        val session = engine.sessions[sessionId] ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED)
        // 先验 token 再跑超时收敛：未鉴权的调用方不该能驱动整表收敛（那是写操作）。
        if (token.isNullOrBlank() || token != session.token) {
            return ApiResponse.error(TransferErrorCode.TOKEN_MISMATCH)
        }
        engine.enforceTimeouts()
        return ApiResponse.ok(
            JSONObject().apply {
                put("sessionId", session.sessionId)
                put("state", session.state.wire)
                put("accepted", engine.acceptedArray(session))
            },
        )
    }

    suspend fun upload(
        sessionId: String,
        fileId: String,
        offset: Long,
        token: String?,
        body: InputStream,
        contentLength: Long,
    ): ApiResponse {
        val session = engine.sessions[sessionId] ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED)
        if (token.isNullOrBlank() || token != session.token) {
            return ApiResponse.error(TransferErrorCode.TOKEN_MISMATCH)
        }
        if (session.state != TransferStatus.TRANSFERRING) {
            return ApiResponse.error(
                TransferErrorCode.SESSION_EXPIRED,
                "会话状态 ${session.state.wire} 不可上传",
            )
        }
        val incoming = session.files[fileId]
            ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED, "文件不在会话内")
        // 续传偏移以 `.part` 实际大小为准：进程被回收重启后内存值是 0，磁盘才是真相
        val expectedOffset = engine.resumableOffset(incoming)
        incoming.received = expectedOffset
        if (offset != expectedOffset) {
            return ApiResponse.error(
                TransferErrorCode.OFFSET_MISMATCH,
                "offset=$offset 与已收 $expectedOffset 不一致",
            )
        }
        val remaining = incoming.file.size - offset
        if (remaining < 0L) return ApiResponse.error(TransferErrorCode.OFFSET_MISMATCH)
        if (contentLength > remaining) {
            return ApiResponse.error(
                TransferErrorCode.OFFSET_MISMATCH,
                "请求体 $contentLength 字节超出剩余 $remaining",
            )
        }
        if (engine.freeBytes() < remaining + TransferEngine.DISK_MARGIN_BYTES) {
            return ApiResponse.error(TransferErrorCode.DISK_FULL)
        }

        val part = StreamCopier.partFile(engine.downloads.partDir(), incoming.file.fileId, incoming.file.sha256)
        incoming.part = part
        val append = part.exists() && part.length() == offset
        val targetOffset = if (append) offset else 0L
        val writeLength = if (append) remaining else incoming.file.size
        if (!append && part.exists()) part.delete()

        var lastPatched = targetOffset
        // 进度发射节流（150ms）：64KiB 一块就发射一次会把 UI 主线程打满；真实字节与
        // `lastProgressAtMs` 仍逐块更新，只有 StateFlow 发射被合并
        var lastEmitMs = ProgressThrottle.NO_EMIT
        val result = try {
            FileOutputStream(part, append).use { output ->
                StreamCopier.copy(
                    input = body,
                    output = output,
                    expectedTotal = writeLength,
                    onChunk = { copied ->
                        val received = targetOffset + copied
                        incoming.received = received
                        val now = System.currentTimeMillis()
                        session.lastProgressAtMs = now
                        if (ProgressThrottle.shouldEmit(lastEmitMs, now)) {
                            lastEmitMs = now
                            engine.patchTask(sessionId, fileId) {
                                it.copy(
                                    receivedBytes = received,
                                    bytesPerSecond = engine.speedFor(it, received, now),
                                    updatedAtMs = now,
                                )
                            }
                        }
                        lastPatched = received
                    },
                )
            }
        } catch (e: IOException) {
            Log.w(TransferEngine.TAG, "写入 ${incoming.file.name} 失败: ${e.message}")
            return ApiResponse.error(TransferErrorCode.DISK_FULL, "写入失败：${e.message}")
        }
        incoming.received = targetOffset + result.bytesCopied
        if (lastPatched != incoming.received) {
            engine.patchTask(sessionId, fileId) {
                it.copy(receivedBytes = incoming.received, updatedAtMs = System.currentTimeMillis())
            }
        }

        if (incoming.received < incoming.file.size) {
            // 分片上传：对端可继续从 offset 续传
            return ApiResponse.ok(
                JSONObject().apply {
                    put("fileId", fileId)
                    put("received", incoming.received)
                    put("sha256", "")
                },
            )
        }

        return finishFile(session, incoming, part)
    }

    /**
     * `POST /cancel`：中止会话。
     *
     * 两道校验（缺一不可）：`token` 必须等于会话 token（与 [upload] 同口径），
     * `senderDeviceId` 必须等于 `session.senderDeviceId` —— 只有本次会话的发送方可以中止它。
     */
    suspend fun cancel(sessionId: String, token: String?, senderDeviceId: String?): ApiResponse {
        val session = engine.sessions[sessionId] ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED)
        if (token.isNullOrBlank() || token != session.token) {
            return ApiResponse.error(TransferErrorCode.TOKEN_MISMATCH)
        }
        if (senderDeviceId.isNullOrBlank() || senderDeviceId != session.senderDeviceId) {
            return ApiResponse.error(TransferErrorCode.TOKEN_MISMATCH, "sender 与会话发送方不一致")
        }
        session.state = TransferStatus.CANCELLED
        session.pin = null
        session.files.values.forEach { it.part?.let { p -> if (p.exists()) p.delete() } }
        if (engine._pinPrompt.value?.sessionId == sessionId) engine._pinPrompt.value = null
        engine.refreshSessionTasks(session)
        return ApiResponse.ok(JSONObject().put("sessionId", sessionId))
    }

    /** 单文件收尾（校验 → 落盘 → 发布）；实现在 TransferFileFinisher。 */
    private suspend fun finishFile(session: IncomingSession, incoming: IncomingFile, part: File): ApiResponse =
        engine.fileFinisher.finishFile(session, incoming, part)
}