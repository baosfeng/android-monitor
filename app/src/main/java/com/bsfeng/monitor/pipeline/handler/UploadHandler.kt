package com.bsfeng.monitor.pipeline.handler

import android.util.Log
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.pipeline.EventHandler
import com.bsfeng.monitor.pipeline.RawEvent

/**
 * UploadHandler 负责将 RawEvent 写入 UploadQueue 缓冲并触发批量上传。
 *
 * 当前为轻量实现：
 * - 每次 handle() 将事件 enqueue 到 UploadQueue（供后续扩展使用，如定时刷出）；
 * - 立即 drain() 清空缓冲并记录日志，避免缓冲只进不出导致内存泄漏；
 * - 同时触发 reporter.uploadBatch() 从数据库读取未上报事件并上传。
 *
 * 后续可改为按阈值或定时批量 drain + 上传，替代每次 handle 都触发 uploadBatch。
 */
class UploadHandler(
    private val uploadQueue: UploadQueue,
    private val reporter: EventReporter
) : EventHandler {
    override val id = "upload"

    override suspend fun handle(event: RawEvent) {
        // 1. 入队缓冲
        uploadQueue.enqueue(event)

        // 2. drain 缓冲并记录日志，防止内存泄漏
        val drained = uploadQueue.drain()
        if (drained.isNotEmpty()) {
            Log.d(TAG, "Drained ${drained.size} events from upload queue")
        }

        // 3. 触发批量上传（从数据库读取未上报事件）
        try {
            reporter.uploadBatch()
        } catch (e: Exception) {
            Log.e(TAG, "uploadBatch failed", e)
        }
    }

    companion object {
        private const val TAG = "UploadHandler"
    }
}
