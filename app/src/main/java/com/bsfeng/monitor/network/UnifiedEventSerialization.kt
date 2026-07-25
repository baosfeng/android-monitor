package com.bsfeng.monitor.network

import com.bsfeng.monitor.domain.model.UnifiedEvent
import org.json.JSONObject

/**
 * UnifiedEvent JSON 序列化扩展（替换原来的 toJsonObject 方法）。
 */
fun UnifiedEvent.toJsonObject(): JSONObject = JSONObject().apply {
    put("eventId", eventId)
    put("eventType", eventType)
    put("eventTypeName", eventType)
    put("summary", summary)
    put("timestamp", timestamp)
    put("deviceId", deviceId)
    put("extra", JSONObject(extra))
}
