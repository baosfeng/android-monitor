package com.bsfeng.monitor.pipeline.handler

import com.bsfeng.monitor.pipeline.RawEvent

/**
 * UploadQueue 接收 RawEvent 并缓冲，由 UploadHandler 负责定期或按阈值批量发送。
 * 当前为轻量实现：直接转发给 EventReporter 的 uploadBatch()。
 * 后续可改为内部缓冲 + 定时批量发送。
 */
class UploadQueue {
    private val buffer = mutableListOf<RawEvent>()

    @Synchronized
    fun enqueue(event: RawEvent) {
        buffer.add(event)
    }

    @Synchronized
    fun drain(): List<RawEvent> {
        val events = buffer.toList()
        buffer.clear()
        return events
    }

    val size: Int
        @Synchronized get() = buffer.size
}
