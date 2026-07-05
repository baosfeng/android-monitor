package com.bsfeng.monitor.pipeline.handler

import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.pipeline.EventHandler
import com.bsfeng.monitor.pipeline.RawEvent

class UploadHandler(
    private val uploadQueue: UploadQueue,
    private val reporter: EventReporter
) : EventHandler {
    override val id = "upload"

    override suspend fun handle(event: RawEvent) {
        uploadQueue.enqueue(event)
        // 当前实现：每次 handle 触发一次批量上传
        // 后续可改为定时触发或按阈值触发
        reporter.uploadBatch()
    }
}
