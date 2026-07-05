package com.bsfeng.monitor.pipeline

import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.event.ScreenContent
import org.json.JSONObject

data class RawEventData(
    val eventType: EventType,
    val packageName: String? = null,
    val className: String? = null,
    val summary: String = "",
    val interactionDetails: Map<String, String> = emptyMap(),
    val screenContent: ScreenContent? = null,
    val rawExtra: Map<String, String> = emptyMap(),
    val structuredData: JSONObject? = null
)

data class SystemStateSnapshot(
    val batteryLevel: Int? = null,
    val networkType: String? = null,
    val memoryAvailable: Long? = null,
    val isCharging: Boolean? = null
)

data class ContextFrame(
    val timestampMs: Long,
    val packageName: String,
    val className: String,
    val screenLabel: String,
    val screenDetails: JSONObject? = null,
    val source: String? = null
)

data class RawEvent(
    val eventId: String = java.util.UUID.randomUUID().toString(),
    val eventType: EventType,
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceId: String,
    val packageName: String? = null,
    val className: String? = null,
    val summary: String = "",
    val interactionDetails: Map<String, String> = emptyMap(),
    val screenContent: ScreenContent? = null,
    val systemState: SystemStateSnapshot? = null,
    val rawExtra: Map<String, String> = emptyMap(),
    val structuredData: JSONObject? = null,
    val navigationContext: List<ContextFrame> = emptyList()
)
