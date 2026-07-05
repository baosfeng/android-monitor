package com.bsfeng.monitor.pipeline

interface EventHandler {
    val id: String
    suspend fun handle(event: RawEvent)
}

interface EventPipeline {
    fun register(handler: EventHandler)
    fun unregister(handlerId: String)
    fun dispatch(event: RawEvent)
}
