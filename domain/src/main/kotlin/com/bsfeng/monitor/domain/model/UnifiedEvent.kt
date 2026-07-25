package com.bsfeng.monitor.domain.model

/**
 * 统一事件模型（领域层），不含 JSON 序列化依赖。
 * 用于在 Domain 层表示待上报的事件数据。
 */
data class UnifiedEvent(
    val eventId: String,
    val eventType: String,
    val timestamp: Long,
    val deviceId: String,
    val summary: String = "",
    val extra: Map<String, String> = emptyMap()
)
