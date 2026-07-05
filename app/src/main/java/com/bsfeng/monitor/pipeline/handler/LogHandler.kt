package com.bsfeng.monitor.pipeline.handler

import android.util.Log
import com.bsfeng.monitor.pipeline.EventHandler
import com.bsfeng.monitor.pipeline.RawEvent

class LogHandler : EventHandler {
    override val id = "log"

    override suspend fun handle(event: RawEvent) {
        Log.d(TAG, buildString {
            append("[${event.eventType.name}] ${event.summary}")
            if (event.navigationContext.isNotEmpty()) {
                append(" | 导航链路: ")
                append(event.navigationContext.joinToString(" → ") { it.screenLabel })
            }
            if (event.structuredData != null) {
                append(" | data: ${event.structuredData}")
            }
        })
    }

    companion object {
        private const val TAG = "MonitorEvent"
    }
}
