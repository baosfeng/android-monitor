package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：发送侧编排（/offer → PIN → 逐文件上传）。
// 只决定「提交什么、怎么推进状态」，字节流与重试在 TransferSendUploader。

import android.net.Uri
import android.util.Log
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.transfer.transport.ApiResponse
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** 发送侧编排器。 */
internal class TransferSendCoordinator(private val engine: TransferEngine) {

    private val appContext get() = engine.appContext
    private val settings get() = engine.settings
    private val client get() = engine.client
    private val scope get() = engine.scope
    private val outgoing get() = engine.outgoing
    private val _tasks get() = engine._tasks
    private val _pinPrompt get() = engine._pinPrompt

    // 待确认的发送（PIN 流程）：**只有** engine.pendingSend 这一处状态。
    // 这里曾另有一个同名字段：写入点在 sendFilesInternal、读取点在 submitPin，而 submitPin
    // 读的是本类字段 —— 该字段全仓没有写入点，于是首行的空值提前返回恒成立：对端弹 PIN、
    // 发送端输完 PIN 什么都不发生，只能等 60 秒确认超时收敛（R1）。
    private val pendingSend: PendingSend? get() = engine.pendingSend

    private val TokenHeader = TransferEngine.TokenHeader
    private val PairKeyHeader = TransferEngine.PairKeyHeader

    /** 把选中的 URI 发给 [peer]（后台执行，进度写回 [tasks]）。 */
    fun sendFiles(peer: PeerDevice, uris: List<Uri>) {
        if (uris.isEmpty()) return
        engine.scope.launch { sendFilesInternal(peer, uris) }
    }

    private suspend fun sendFilesInternal(peer: PeerDevice, uris: List<Uri>) {
        val sources = uris.mapNotNull { TransferSourceReader.read(appContext, it) }
        if (sources.isEmpty()) {
            Log.w(TransferEngine.TAG, "没有可发送的文件")
            return
        }
        val sessionId = UUID.randomUUID().toString()
        sources.forEachIndexed { index, source ->
            val fileId = "f${index + 1}"
            // 先登记源 Uri 再建任务行：upsertTask 会立刻落库（首次出现），落库时按方向取
            // sourceFiles —— 顺序反了就会把发送任务的 Uri 存成 null，重发能力当场丢失。
            engine.sourceFiles[engine.taskKey(sessionId, fileId)] = source.uri
            engine.upsertTask(
                com.bsfeng.monitor.domain.transfer.TransferTask(
                    sessionId = sessionId,
                    peer = peer,
                    direction = TransferDirection.SEND,
                    file = TransferFile(fileId, source.name, source.size, source.sha256, source.mime),
                    status = TransferStatus.OFFERED,
                    updatedAtMs = System.currentTimeMillis(),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }

        val offerBody = TransferWireCodec.buildOfferJson(sources, engine.settings.deviceId, engine.settings.alias)
        val headerPairs = listOfNotNull(
            engine.settings.pairingKey(peer.deviceId)?.let { Pair(PairKeyHeader, it) }
        )
        val response = runCatching {
            engine.client.postJson(
                MiniHttpClient.buildUrl(peer.baseUrl, "offer"),
                offerBody,
                headerPairs.toMap(),
            )
        }.getOrElse { e ->
            engine.failAll(sessionId, ApiResponse.PROTOCOL_ERROR, "提交清单失败：${e.message}")
            return
        }
        val api = response.api ?: run {
            engine.failAll(sessionId, ApiResponse.PROTOCOL_ERROR, "对端响应非法（HTTP ${response.httpCode}）")
            return
        }
        if (!api.isOk) {
            engine.failAll(sessionId, api.code, api.message)
            return
        }
        api.string("pairingKey").takeIf { it.isNotBlank() }?.let {
            engine.settings.savePairingKey(peer.deviceId, it)
        }
        val remoteSessionId = api.string("sessionId", sessionId)
        val token = api.string("token")
        when (TransferStatus.fromWire(api.string("state"))) {
            TransferStatus.TRANSFERRING -> {
                // 任务行沿用本端会话 id（offered 时就按它落库 / 展示）：对端 sessionId 只用于 HTTP，
                // 否则进度回写找不到任务行，卡片会永远停在「已提交清单」。
                engine.outgoing[sessionId] = OutgoingSession(peer, remoteSessionId, token, sources)
                uploadAll(sessionId, remoteSessionId, peer, sources, token, TransferWireCodec.readAccepted(api))
            }

            TransferStatus.AWAITING_CONFIRM -> {
                // 单一状态源：submitPin / dismissPin 都读 engine.pendingSend（TransferSessionActions 同口径）
                engine.pendingSend = PendingSend(peer, sources, remoteSessionId)
                engine.renameTasks(sessionId, remoteSessionId)
                engine._pinPrompt.value = TransferEngine.PinPrompt(
                    sessionId = remoteSessionId,
                    peerAlias = peer.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS },
                    direction = TransferDirection.SEND,
                    pin = null,
                    fileName = sources.first().name,
                )
            }

            TransferStatus.DENIED -> engine.failAll(remoteSessionId, TransferErrorCode.USER_DENIED, "对方拒绝接收")

            else -> engine.failAll(
                remoteSessionId,
                ApiResponse.PROTOCOL_ERROR,
                "未知会话状态：${api.string("state")}",
            )
        }
    }

    /** 发送端输入 PIN 后继续（协议 §5）。 */
    fun submitPin(pin: String) {
        val pending = pendingSend ?: return
        if (pin.length != 4 || pin.any { !it.isDigit() }) return
        engine.scope.launch {
            val body = TransferWireCodec.buildOfferJson(pending.sources, engine.settings.deviceId, engine.settings.alias, pin)
            val headerPairs = listOfNotNull(
                engine.settings.pairingKey(pending.peer.deviceId)?.let { Pair(PairKeyHeader, it) },
            )
            val response = runCatching {
                engine.client.postJson(
                    MiniHttpClient.buildUrl(pending.peer.baseUrl, "offer"),
                    body,
                    headerPairs.toMap(),
                )
            }.getOrElse { e ->
                engine.failAll(pending.sessionId, ApiResponse.PROTOCOL_ERROR, "PIN 校验请求失败：${e.message}")
                engine.pendingSend = null
                engine._pinPrompt.value = null
                return@launch
            }
            val api = response.api
            if (api == null) {
                engine.failAll(pending.sessionId, ApiResponse.PROTOCOL_ERROR, "PIN 校验响应非法")
                engine.pendingSend = null
                engine._pinPrompt.value = null
                return@launch
            }
            if (!api.isOk) {
                engine.failAll(pending.sessionId, api.code, api.message)
                engine.pendingSend = null
                engine._pinPrompt.value = null
                return@launch
            }
            api.string("pairingKey").takeIf { it.isNotBlank() }?.let {
                engine.settings.savePairingKey(pending.peer.deviceId, it)
            }
            if (TransferStatus.fromWire(api.string("state")) != TransferStatus.TRANSFERRING) {
                // PIN 错误：保持弹层，提示重试
                engine._pinPrompt.value = engine._pinPrompt.value?.copy(message = "PIN 不正确，请重试")
                return@launch
            }
            engine.pendingSend = null
            engine._pinPrompt.value = null
            val token = api.string("token")
            engine.outgoing[pending.sessionId] =
                OutgoingSession(pending.peer, pending.sessionId, token, pending.sources)
            uploadAll(pending.sessionId, pending.sessionId, pending.peer, pending.sources, token, TransferWireCodec.readAccepted(api))
        }
    }

    private suspend fun uploadAll(
        taskSessionId: String,
        remoteSessionId: String,
        peer: PeerDevice,
        sources: List<SendSource>,
        token: String,
        offsets: Map<String, Long>,
    ) {
        val uploader = TransferSendUploader(
            appContext = appContext,
            client = client,
            tokenHeader = TokenHeader,
            onProgress = { fileId, received ->
                engine.patchTask(taskSessionId, fileId) {
                    it.copy(
                        receivedBytes = received,
                        bytesPerSecond = engine.speedFor(it, received, System.currentTimeMillis()),
                        status = TransferStatus.TRANSFERRING,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            },
            onCompleted = { fileId, totalSize ->
                engine.patchTask(taskSessionId, fileId) {
                    it.copy(
                        status = TransferStatus.COMPLETED,
                        receivedBytes = totalSize,
                        bytesPerSecond = 0L,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            },
            onFailed = { fileId, code, message ->
                engine.failTask(taskSessionId, fileId, code, message)
                engine.playSound(TransferSoundPlayer.Event.FAILURE)
            },
            isTerminal = { fileId ->
                engine._tasks.value.any {
                    it.sessionId == taskSessionId && it.file.fileId == fileId && it.status.isTerminal
                }
            },
            parseResponse = { tsid, fid, src, resp -> parseUploadResponse(tsid, fid, src, resp) },
        )
        val ok = uploader.uploadAll(
            taskSessionId = taskSessionId,
            remoteSessionId = remoteSessionId,
            peer = peer,
            sources = sources,
            token = token,
            offsets = offsets,
            isTracked = { engine.outgoing[taskSessionId] != null },
        )
        if (!ok) {
            engine.outgoing.remove(taskSessionId)
            return
        }
        // 只有仍在跟踪的会话才播完成音：已被超时收敛的不再「假装成功」
        if (engine.outgoing.remove(taskSessionId) != null) engine.playSound(TransferSoundPlayer.Event.COMPLETE)
    }

    private fun parseUploadResponse(
        taskSessionId: String,
        fileId: String,
        source: SendSource,
        response: MiniHttpClient.HttpResponse,
    ): Boolean {
        val api = response.api
        if (api == null || !api.isOk) {
            if (api != null && api.code == TransferErrorCode.SHA256_MISMATCH) {
                engine.failTask(taskSessionId, fileId, api.code, api.message)
                return false
            }
            Log.w(TransferEngine.TAG, "上传响应异常：HTTP ${response.httpCode} ${response.body.take(120)}")
            return false
        }
        val remoteSha = api.string("sha256")
        if (remoteSha.isNotBlank() && !remoteSha.equals(source.sha256, ignoreCase = true)) {
            engine.failTask(taskSessionId, fileId, TransferErrorCode.SHA256_MISMATCH, "sha256 不一致")
            engine.playSound(TransferSoundPlayer.Event.FAILURE)
            return false
        }
        return api.long("received", -1L) < 0L || api.long("received") >= source.size
    }
}