package com.bsfeng.monitor.data.repository

import com.bsfeng.monitor.data.datasource.LocalEventDataSource
import com.bsfeng.monitor.data.mapper.EventMapper
import com.bsfeng.monitor.domain.model.EventType
import com.bsfeng.monitor.domain.model.UnifiedEvent
import com.bsfeng.monitor.domain.repository.EventRepository

/**
 * 事件仓储实现。
 * 协调本地数据源（SQLite）和远程数据源（HTTP）的事件存取。
 */
class EventRepositoryImpl(
    private val localDataSource: LocalEventDataSource
) : EventRepository {

    override suspend fun save(eventType: EventType, summary: String, extra: Map<String, String>) {
        // 暂不实现 —— DatabaseHandler 已通过 Pipeline 直接写入 DB
    }

    override suspend fun getUnsentEvents(limit: Int): List<UnifiedEvent> {
        val entities = localDataSource.getUnsentEvents(limit)
        return EventMapper.toUnifiedEvents(entities)
    }

    override suspend fun markUploaded(eventIds: List<String>) {
        localDataSource.markUploaded(eventIds)
    }

    override suspend fun cleanOldEvents(before: Long) {
        localDataSource.cleanOldEvents(before)
    }
}
