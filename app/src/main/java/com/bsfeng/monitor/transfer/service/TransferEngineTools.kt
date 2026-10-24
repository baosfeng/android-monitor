package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：引擎内部工具与转发（会话创建 / 审批应答 /
// 速率换算 / 接收统计与清理 / 续传偏移 / 超时调度 / 任务行刷新）。都以扩展函数留在同包，
// 便于直接读写引擎内部字段。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.transport.ApiResponse
import com.bsfeng.monitor.transfer.service.TransferEngine.Companion.SESSION_KEEP_MS
import com.bsfeng.monitor.transfer.service.TransferEngine.Companion.TAG
import com.bsfeng.monitor.transfer.policy.ProgressSpeed
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────── 内部工具 ───────────────────────────

internal fun TransferEngine.createIncomingSession(
    senderDeviceId: String,
    senderAlias: String,
    files: List<TransferFile>,
): IncomingSession = receiveSupport.createIncomingSession(senderDeviceId, senderAlias, files)

internal fun TransferEngine.approve(session: IncomingSession, persistedPairKey: String?): ApiResponse =
    receiveSupport.approve(session, persistedPairKey)

internal fun TransferEngine.approvedResponse(session: IncomingSession, pairingKey: String?): ApiResponse =
    receiveSupport.approvedResponse(session, pairingKey)

internal fun TransferEngine.pendingResponse(session: IncomingSession, message: String): ApiResponse =
    receiveSupport.pendingResponse(session, message)

internal fun TransferEngine.acceptedArray(session: IncomingSession): JSONArray = receiveSupport.acceptedArray(session)

internal fun TransferEngine.speedFor(previous: TransferTask, received: Long, nowMs: Long): Long =
    ProgressSpeed.bytesPerSecond(
        previousReceived = previous.receivedBytes,
        previousBps = previous.bytesPerSecond,
        previousAtMs = previous.updatedAtMs,
        received = received,
        nowMs = nowMs,
    )

/** 接收目录占用（`Downloads/局域网互传/` 的文件数与总大小）。 */
internal fun TransferEngine.receivedStatsImpl(): DownloadsSink.ReceivedStats = downloads.stats()

/**
 * 清理接收目录并清空对应历史：删除 `Downloads/局域网互传/` 下的全部文件记录，
 * 同时移除「接收 + 已完成」的任务行（失败/取消记录保留，其 `.part` 仍在缓存目录）。
 *
 * @return 实际删除的文件条数。
 */
internal fun TransferEngine.clearReceivedHistoryImpl(): Int {
    val removed = downloads.clearAll()
    publishedFiles.clear()
    scope.launch {
        runCatching { taskStore.deleteReceived() }
            .onFailure { Log.w(TAG, "接收历史清空失败: ${it.message}") }
    }
    _tasks.update { list ->
        list.filterNot { it.direction == TransferDirection.RECV && it.status == TransferStatus.COMPLETED }
    }
    return removed
}

/**
 * 续传偏移：以 `.part` 实际大小为准（协议 §6 断点续传）。
 *
 * 接收端进程被系统回收后重启，会话内存里的 `received` 是 0，但磁盘 `.part` 已有部分字节；
 * 若按内存值应答 `offset=0`，发送端会从头传、随后被严格校验打成 4003，续传能力丢失。
 *
 * - `.part` 不存在 → 0；
 * - 长度在 `0..file.size` → 该长度；
 * - 长度超出声明大小 → 脏数据，删除后按 0；
 * - 已收满（`received >= size`）→ 返回 size（此时 `.part` 已落盘并删除，不能回落成 0）。
 */
internal fun TransferEngine.resumableOffset(incoming: IncomingFile): Long {
    val result = TransferResumeOffset.compute(
        partDir = downloads.partDir(),
        fileId = incoming.file.fileId,
        declaredSize = incoming.file.size,
        memoryReceived = incoming.received,
    )
    incoming.part = result.part
    return result.offset
}

internal fun TransferEngine.scheduleConfirmTimeout(session: IncomingSession) {
    scope.launch {
        delay(confirmTimeoutMs)
        if (session.state == TransferStatus.AWAITING_CONFIRM &&
            System.currentTimeMillis() >= session.confirmDeadlineMs
        ) {
            session.state = TransferStatus.FAILED
            session.pin = null
            // 先落任务终态、再收起弹层：外部（UI / 测试）看到弹层消失时任务一定已经不在「进行中」，
            // 不会出现「弹层没了、卡片还显示等待确认」的中间态。
            // 超时只静默收起 PIN 弹层：不播失败音、不震（避免打扰）
            refreshSessionTasks(session, notifyFailure = false)
            if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
            Log.i(TAG, "会话 ${session.sessionId} 确认超时（${confirmTimeoutMs}ms）")
        }
    }
}

internal fun TransferEngine.pruneExpiredSessions() {
    val now = System.currentTimeMillis()
    val expired = sessions.values.filter {
        it.state.isTerminal && now - it.createdAtMs > SESSION_KEEP_MS
    }
    expired.forEach { sessions.remove(it.sessionId) }
}

internal fun TransferEngine.refreshSessionTasks(session: IncomingSession, notifyFailure: Boolean = true) {
    session.files.values.forEach { incoming ->
        patchTask(session.sessionId, incoming.file.fileId) {
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
        playSound(TransferSoundPlayer.Event.FAILURE)
    }
}

internal fun TransferEngine.parseFiles(array: JSONArray): List<TransferFile>? {
    val files = mutableListOf<TransferFile>()
    for (i in 0 until array.length()) {
        val json = array.optJSONObject(i) ?: return null
        val fileId = json.optString("fileId", "")
        val name = json.optString("name", "")
        val size = json.optLong("size", -1L)
        val sha = json.optString("sha256", "")
        val mime = json.optString("mime", "application/octet-stream")
        if (fileId.isBlank() || name.isBlank() || size < 0L) return null
        files += TransferFile(fileId, name, size, sha, mime)
    }
    return files
}

internal fun TransferEngine.buildOfferJson(sources: List<SendSource>, pin: String? = null): String =
    JSONObject().apply {
        put("senderDeviceId", settings.deviceId)
        put("senderAlias", settings.alias)
        put(
            "files",
            JSONArray().apply {
                sources.forEachIndexed { index, source ->
                    put(
                        JSONObject().apply {
                            put("fileId", "f${index + 1}")
                            put("name", source.name)
                            put("size", source.size)
                            put("sha256", source.sha256)
                            put("mime", source.mime)
                        },
                    )
                }
            },
        )
        if (pin != null) put("pin", pin)
    }.toString()

