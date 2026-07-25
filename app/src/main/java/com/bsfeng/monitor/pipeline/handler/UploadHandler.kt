package com.bsfeng.monitor.pipeline.handler

import android.util.Log
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.pipeline.EventHandler
import com.bsfeng.monitor.pipeline.RawEvent
import kotlinx.coroutines.*

/**
 * UploadHandler 负责将 RawEvent 写入 UploadQueue 缓冲并按阈值/定时批量上传。
 *
 * 改进后的策略：
 * - 事件先入 UploadQueue 缓冲
 * - 缓冲达到 [batchThreshold] 条时立即触发上传
 * - 同时启动定时协程，间隔 [batchIntervalMs] 到期自动刷出
 * - 不再每次 handle() 都触发 uploadBatch，大幅减少网络请求
 */
class UploadHandler(
    private val uploadQueue: UploadQueue,
    private val reporter: EventReporter,
    private val batchThreshold: Int = 20,
    private val batchIntervalMs: Long = 30_000L
) : EventHandler {
    override val id = "upload"

    private var flushJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun handle(event: RawEvent) {
        // 1. 入队缓冲
        uploadQueue.enqueue(event)

        // 2. 触发条件：达到阈值或首次触发时启动定时器
        if (uploadQueue.size >= batchThreshold) {
            doUpload()
        } else if (flushJob == null || flushJob?.isCompleted != false) {
            startTimerFlush()
        }
    }

    /**
     * 启动定时刷出协程，间隔到期后触发上传。
     */
    private fun startTimerFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(batchIntervalMs)
            doUpload()
        }
    }

    /**
     * 执行批量上传：drain 缓冲 + 触发上报。
     */
    private suspend fun doUpload() {
        flushJob?.cancel()
        flushJob = null

        // drain 缓冲并记录日志
        val drained = uploadQueue.drain()
        if (drained.isNotEmpty()) {
            Log.d(TAG, "Flushing ${drained.size} events (threshold=$batchThreshold, interval=${batchIntervalMs}ms)")
        }

        // 触发批量上传（从数据库读取未上报事件）
        try {
            val result = reporter.uploadBatch()
            result.onSuccess { count ->
                Log.d(TAG, "Uploaded $count events successfully")
            }.onFailure { e ->
                Log.w(TAG, "uploadBatch failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadBatch failed", e)
        }
    }

    /** 停止定时刷出（用于服务销毁时清理） */
    fun shutdown() {
        flushJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "UploadHandler"
    }
}
