package com.bsfeng.monitor.pipeline

import com.bsfeng.monitor.util.AppUtils

class RawEventCollector(
    private val pipeline: EventPipeline,
    private val navTracker: NavigationContextTracker
) {
    fun emit(source: EventSource) {
        val rawData = source.collectRaw()
        val event = buildRawEvent(rawData)
        pipeline.dispatch(event)
    }

    private fun buildRawEvent(data: RawEventData): RawEvent = RawEvent(
        eventType = data.eventType,
        timestampMs = System.currentTimeMillis(),
        deviceId = AppUtils.getDeviceId(),
        packageName = data.packageName,
        className = data.className,
        summary = data.summary,
        interactionDetails = data.interactionDetails,
        screenContent = data.screenContent,
        systemState = null, // 后续可扩展 captureSystemState()
        rawExtra = data.rawExtra,
        structuredData = data.structuredData,
        navigationContext = navTracker.getCurrentContext()
    )
}
