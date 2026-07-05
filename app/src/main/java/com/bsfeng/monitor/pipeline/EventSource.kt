package com.bsfeng.monitor.pipeline

interface EventSource {
    fun collectRaw(): RawEventData
}
