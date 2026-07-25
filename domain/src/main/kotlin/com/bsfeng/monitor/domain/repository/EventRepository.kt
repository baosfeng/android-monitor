package com.bsfeng.monitor.domain.repository

import com.bsfeng.monitor.domain.model.EventType
import com.bsfeng.monitor.domain.model.UnifiedEvent

/**
 * 事件仓储接口 — 定义事件持久化和查询的边界。
 * 由 Data 层实现，Domain 层仅依赖此接口。
 */
interface EventRepository {

    /**
     * 保存事件。
     */
    suspend fun save(eventType: EventType, summary: String, extra: Map<String, String> = emptyMap())

    /**
     * 获取未上报的事件列表。
     */
    suspend fun getUnsentEvents(limit: Int): List<UnifiedEvent>

    /**
     * 将指定事件标记为已上报。
     */
    suspend fun markUploaded(eventIds: List<String>)

    /**
     * 清理旧事件。
     */
    suspend fun cleanOldEvents(before: Long)
}
