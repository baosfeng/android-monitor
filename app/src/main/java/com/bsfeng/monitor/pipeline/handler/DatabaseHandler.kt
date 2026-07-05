package com.bsfeng.monitor.pipeline.handler

import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.pipeline.EventHandler
import com.bsfeng.monitor.pipeline.RawEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DatabaseHandler(private val db: DatabaseHelper) : EventHandler {
    override val id = "database"

    override suspend fun handle(event: RawEvent) {
        val entity = EventEntity.fromRawEvent(event)
        db.insert(entity)
    }
}
