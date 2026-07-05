package com.bsfeng.monitor.data

import com.bsfeng.monitor.pipeline.RawEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 事件数据实体 — 纯数据类，由 DatabaseHelper 直接读写
 *
 * - summary: 用户友好摘要，如"打开了 微信""进入了聊天界面"
 * - extraJson: 技术细节 JSON（包名、类名、时长等）
 */
data class EventEntity(
    val eventId: String,
    val eventType: String,
    val timestampMs: Long,
    val deviceId: String,
    val summary: String = "",
    val extraJson: String,
    val isUploaded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromEvent(
            eventId: String,
            eventType: String,
            extraJson: String,
            deviceId: String,
            summary: String = ""
        ): EventEntity = EventEntity(
            eventId = eventId,
            eventType = eventType,
            timestampMs = System.currentTimeMillis(),
            deviceId = deviceId,
            summary = summary,
            extraJson = extraJson,
            isUploaded = false
        )

        fun fromRawEvent(event: RawEvent): EventEntity {
            val extraJson = JSONObject().apply {
                put("interactionDetails", JSONObject(event.interactionDetails))
                put("rawExtra", JSONObject(event.rawExtra))
                if (event.structuredData != null) put("structuredData", event.structuredData)
                if (event.navigationContext.isNotEmpty()) {
                    put("navigationContext", JSONArray(event.navigationContext.map { frame ->
                        JSONObject().apply {
                            put("timestampMs", frame.timestampMs)
                            put("packageName", frame.packageName)
                            put("className", frame.className)
                            put("screenLabel", frame.screenLabel)
                            frame.source?.let { put("source", it) }
                        }
                    }))
                }
            }.toString()
            return EventEntity(
                eventId = event.eventId,
                eventType = event.eventType.name,
                timestampMs = event.timestampMs,
                deviceId = event.deviceId,
                summary = event.summary,
                extraJson = extraJson,
                isUploaded = false,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
