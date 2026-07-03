package com.bsfeng.monitor.event

import org.json.JSONObject
import java.util.UUID

data class UnifiedEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String,
    val extra: Map<String, String> = emptyMap()
) {
/** 事件序列化 — key 使用驼峰命名 */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("eventId", eventId)
        put("eventType", eventType)
        put("eventTypeName", EventType.getChineseName(eventType))
        put("timestamp", timestamp)
        put("deviceId", deviceId)
        put("extra", JSONObject(extra))
    }
}
