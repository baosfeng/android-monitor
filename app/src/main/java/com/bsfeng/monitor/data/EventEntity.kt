package com.bsfeng.monitor.data

/**
 * 事件数据实体 — 纯数据类，由 DatabaseHelper 直接读写
 */
data class EventEntity(
    val eventId: String,
    val eventType: String,
    val timestampMs: Long,
    val deviceId: String,
    val extraJson: String,
    val isUploaded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromEvent(
            eventId: String,
            eventType: String,
            extraJson: String,
            deviceId: String
        ): EventEntity = EventEntity(
            eventId = eventId,
            eventType = eventType,
            timestampMs = System.currentTimeMillis(),
            deviceId = deviceId,
            extraJson = extraJson,
            isUploaded = false
        )
    }
}
