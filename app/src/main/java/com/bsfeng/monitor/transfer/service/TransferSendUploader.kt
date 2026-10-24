package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：发送侧的逐文件上传与断网重试。
// 只负责「把源文件的剩余字节按 offset 推给对端」，进度回写与失败收敛由调用方注入。

import android.content.Context
import android.util.Log
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.RETRY_DELAYS_MS
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.transfer.policy.ProgressThrottle
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 单文件上传 + 重试（协议 §6）。
 *
 * 重试口径：`RETRY_DELAYS_MS` 定义的 1s/2s/4s 三次，耗尽后落 FAILED。
 * 进度发射按 [ProgressThrottle] 节流且**跨重试保持**：不节流时 100MB / 64KiB 每秒约 57 次发射，
 * Compose 每秒全量重组几十次 → 传输中界面白屏。
 */
internal class TransferSendUploader(
    private val appContext: Context,
    private val client: MiniHttpClient,
    private val tokenHeader: String,
    private val onProgress: (fileId: String, received: Long) -> Unit,
    private val onCompleted: (fileId: String, totalSize: Long) -> Unit,
    private val onFailed: (fileId: String, code: Int, message: String) -> Unit,
    private val isTerminal: (fileId: String) -> Boolean,
    private val parseResponse: (
        taskSessionId: String,
        fileId: String,
        source: SendSource,
        response: MiniHttpClient.HttpResponse,
    ) -> Boolean,
) {
    /**
     * 逐个文件上传；任一失败即停（后续文件不再推）。
     *
     * @return 是否全部成功。
     */
    suspend fun uploadAll(
        taskSessionId: String,
        remoteSessionId: String,
        peer: PeerDevice,
        sources: List<SendSource>,
        token: String,
        offsets: Map<String, Long>,
        isTracked: () -> Boolean,
    ): Boolean {
        sources.forEachIndexed { index, source ->
            // 已被超时收敛（条目被摘）：不再继续上传后面的文件
            if (!isTracked()) return false
            val fileId = "f${index + 1}"
            val offset = offsets[fileId] ?: 0L
            val ok = uploadWithRetry(taskSessionId, remoteSessionId, peer, source, fileId, offset, token)
            if (!ok) return false
        }
        return true
    }

    private suspend fun uploadWithRetry(
        taskSessionId: String,
        remoteSessionId: String,
        peer: PeerDevice,
        source: SendSource,
        fileId: String,
        offset: Long,
        token: String,
    ): Boolean {
        var attempt = 0
        // 进度发射节流（150ms，跨重试保持）：状态跃迁 / 终态不经过这里
        var lastEmitMs = ProgressThrottle.NO_EMIT
        while (true) {
            val outcome = runCatching {
                val url = MiniHttpClient.buildUrl(
                    peer.baseUrl,
                    "upload",
                    listOf(
                        "session" to remoteSessionId,
                        "file" to fileId,
                        "offset" to offset.toString(),
                    ),
                )
                val input = appContext.contentResolver.openInputStream(source.uri)
                    ?: throw IOException("无法读取源文件")
                val sent = input.use { stream ->
                    TransferWireCodec.skipFully(stream, offset)
                    client.putStream(
                        url = url,
                        input = stream,
                        contentLength = source.size - offset,
                        headers = mapOf(tokenHeader to token),
                        onProgress = { progress ->
                            val now = System.currentTimeMillis()
                            if (ProgressThrottle.shouldEmit(lastEmitMs, now)) {
                                lastEmitMs = now
                                // 已收敛为终态的任务不再被进度回写唤醒
                                if (!isTerminal(fileId)) onProgress(fileId, offset + progress)
                            }
                        },
                    )
                }
                parseResponse(taskSessionId, fileId, source, sent)
            }
            val success = outcome.getOrElse { e ->
                Log.w(TAG, "上传 $fileId 失败: ${e.message}")
                false
            }
            if (success) {
                // 终态不可被回写翻转（已超时收敛的任务保持失败）
                if (!isTerminal(fileId)) onCompleted(fileId, source.size)
                return true
            }
            if (attempt >= RETRY_DELAYS_MS.size) {
                onFailed(fileId, TransferErrorCode.SESSION_EXPIRED, "断网重试 3 次仍失败")
                return false
            }
            delay(RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }

    private companion object {
        const val TAG = "TransferEngine"
    }
}
