package com.bsfeng.monitor.data.datasource

import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.pipeline.RawEvent

/**
 * 本地事件数据源 — 封装 DatabaseHelper 的读写操作。
 * Data 层内部使用，不对外暴露 SQLite 细节。
 */
class LocalEventDataSource(private val db: DatabaseHelper) {

    fun insert(event: RawEvent) {
        db.insert(EventEntity.fromRawEvent(event))
    }

    fun getUnsentEvents(limit: Int): List<EventEntity> {
        return db.getUnsentEvents(limit)
    }

    fun markUploaded(eventIds: List<String>) {
        db.markUploaded(eventIds)
    }

    fun cleanOldEvents(before: Long) {
        db.cleanOldEvents(before)
    }

    fun countUnsent(): Int = db.countUnsent()
}
