package com.bsfeng.monitor.event

import android.util.Log
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DiagnosticCounters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

object EventCollector {
    private const val TAG = "EventCollector"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun emit(eventType: EventType, extra: Map<String, String> = emptyMap(), summary: String = "") {
        try {
            DiagnosticCounters.emitCalledCount++
            DiagnosticCounters.lastEmittedEventType = eventType.name
            Log.d(TAG, "emit: eventType=${eventType.name}, extra=$extra")
            val event = UnifiedEvent(
                eventType = eventType.name,
                deviceId = AppUtils.getDeviceId(),
                summary = summary,
                extra = extra
            )
            val extraJson = JSONObject(extra).toString()
            val entity = EventEntity.fromEvent(
                eventId = event.eventId,
                eventType = event.eventType,
                extraJson = extraJson,
                deviceId = event.deviceId,
                summary = summary
            )
            Log.d(TAG, "emit: entity created, eventId=${entity.eventId}")
            scope.launch {
                try {
                    val db = DatabaseHelper.getInstance(MonitorApp.instance)
                    db.insert(entity)
                    DiagnosticCounters.emitSuccessCount++
                    Log.d(TAG, "emit: insert success, eventId=${entity.eventId}")
                } catch (e: Exception) {
                    DiagnosticCounters.emitFailCount++
                    DiagnosticCounters.lastErrorMessage = "${e.javaClass.simpleName}: ${e.message}"
                    Log.e(TAG, "emit: insert FAILED, eventId=${entity.eventId}", e)
                }
            }
        } catch (e: Exception) {
            DiagnosticCounters.emitFailCount++
            DiagnosticCounters.lastErrorMessage = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "emit: FAILED before DB insert", e)
        }
    }
}
