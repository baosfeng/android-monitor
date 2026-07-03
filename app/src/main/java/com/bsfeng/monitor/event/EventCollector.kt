package com.bsfeng.monitor.event

import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.util.AppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object EventCollector {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun emit(eventType: EventType, extra: Map<String, String> = emptyMap()) {
        val event = UnifiedEvent(
            eventType = eventType.name,
            deviceId = AppUtils.getDeviceId(),
            extra = extra
        )
        val extraJson = JSONObject(extra).toString()
        val entity = EventEntity.fromEvent(
            eventId = event.eventId,
            eventType = event.eventType,
            extraJson = extraJson,
            deviceId = event.deviceId
        )
        scope.launch {
            DatabaseHelper.getInstance(MonitorApp.instance).insert(entity)
        }
    }
}
