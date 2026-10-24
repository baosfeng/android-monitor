package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferReceiveRoutes.kt 拆出：接收单文件的收尾（校验 → 落盘 → 发布）。
// 校验失败立刻落 FAILED/4005，不让任务挂在「进行中」等 60s 无进展超时。

import android.util.Log
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.transfer.transport.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** 单文件收尾。 */
internal class TransferFileFinisher(private val engine: TransferEngine) {

    suspend fun finishFile(
        session: IncomingSession,
        incoming: IncomingFile,
        part: File,
    ): ApiResponse = withContext(Dispatchers.IO) {
        val actualSha = com.bsfeng.monitor.transfer.transport.StreamCopier.sha256Of(part)
        if (!actualSha.equals(incoming.file.sha256, ignoreCase = true) && incoming.file.sha256.isNotBlank()) {
            part.delete()
            // 校验失败是终态：立刻落 FAILED/4005，而不是让任务挂在「进行中」等 60s 无进展超时
            session.state = TransferStatus.FAILED
            engine.failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.SHA256_MISMATCH, "文件校验失败")
            return@withContext ApiResponse.error(TransferErrorCode.SHA256_MISMATCH)
        }
        val displayName = com.bsfeng.monitor.transfer.transport.StreamCopier.uniqueFileName(
            incoming.file.name,
            engine.downloads.existingNames()::contains,
        )
        val uri = engine.downloads.createPending(displayName, incoming.file.mime)
            ?: run {
                session.state = TransferStatus.FAILED
                engine.failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.DISK_FULL, "无法创建目标文件")
                return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "无法创建目标文件")
            }
        val output = engine.downloads.openOutput(uri)
            ?: run {
                session.state = TransferStatus.FAILED
                engine.failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.DISK_FULL, "无法打开目标文件")
                return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "无法打开目标文件")
            }
        try {
            part.inputStream().use { input ->
                output.use { out -> com.bsfeng.monitor.transfer.transport.StreamCopier.copy(input, out, incoming.file.size) }
            }
            engine.downloads.publish(uri)
            part.delete()
            // 记录落盘产物：用户删除这条已完成任务时一并删除（发送任务不登记，源文件不删）
            engine.publishedFiles[engine.taskKey(session.sessionId, incoming.file.fileId)] = uri
        } catch (e: IOException) {
            engine.downloads.delete(uri)
            Log.w("TransferEngine", "落盘失败: ${e.message}")
            session.state = TransferStatus.FAILED
            engine.failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.DISK_FULL, "落盘失败")
            return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "落盘失败：${e.message}")
        }

        if (session.files.values.all { it.received >= it.file.size }) {
            session.state = TransferStatus.COMPLETED
            if (engine._pinPrompt.value?.sessionId == session.sessionId) engine._pinPrompt.value = null
            engine.playSound(TransferSoundPlayer.Event.COMPLETE)
        }
        engine.refreshSessionTasks(session)
        ApiResponse.ok(
            JSONObject().apply {
                put("fileId", incoming.file.fileId)
                put("received", incoming.received)
                put("sha256", actualSha)
            },
        )
    }
}
