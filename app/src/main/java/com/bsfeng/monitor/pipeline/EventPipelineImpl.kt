package com.bsfeng.monitor.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EventPipelineImpl : EventPipeline {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handlers = mutableMapOf<String, EventHandler>()

    @Synchronized
    override fun register(handler: EventHandler) {
        handlers[handler.id] = handler
    }

    @Synchronized
    override fun unregister(handlerId: String) {
        handlers.remove(handlerId)
    }

    override fun dispatch(event: RawEvent) {
        val snapshot: List<EventHandler>
        synchronized(this) { snapshot = handlers.values.toList() }
        for (handler in snapshot) {
            scope.launch {
                try {
                    handler.handle(event)
                } catch (e: Exception) {
                    try {
                        android.util.Log.e("EventPipeline", "Handler ${handler.id} failed", e)
                    } catch (_: Exception) {
                        System.err.println("EventPipeline: Handler ${handler.id} failed: ${e.message}")
                    }
                }
            }
        }
    }
}
