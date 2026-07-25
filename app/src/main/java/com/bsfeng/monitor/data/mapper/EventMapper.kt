package com.bsfeng.monitor.data.mapper

import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.domain.model.EventType
import com.bsfeng.monitor.domain.model.UnifiedEvent
import org.json.JSONObject

/**
 * EventEntity ↔ 领域模型映射器。
 */
object EventMapper {

    fun toUnifiedEvent(entity: EventEntity): UnifiedEvent {
        val extraMap = try {
            JSONObject(entity.extraJson).let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }
        } catch (_: Exception) { emptyMap() }
        return UnifiedEvent(
            eventId = entity.eventId,
            eventType = entity.eventType,
            timestamp = entity.timestampMs,
            deviceId = entity.deviceId,
            summary = entity.summary,
            extra = extraMap
        )
    }

    fun toUnifiedEvents(entities: List<EventEntity>): List<UnifiedEvent> {
        return entities.map { toUnifiedEvent(it) }
    }
}
