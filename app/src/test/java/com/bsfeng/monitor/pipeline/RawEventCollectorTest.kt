package com.bsfeng.monitor.pipeline

import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.EventType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MonitorApp::class)
class RawEventCollectorTest {

    @Test
    fun `emit passes event to pipeline`() {
        // Robolectric provides a real Android environment, so MonitorApp works
        var received: RawEvent? = null
        val pipeline = object : EventPipeline {
            override fun register(handler: EventHandler) {}
            override fun unregister(handlerId: String) {}
            override fun dispatch(event: RawEvent) { received = event }
        }
        val tracker = NavigationContextTracker()
        val collector = RawEventCollector(pipeline, tracker)
        val source = object : EventSource {
            override fun collectRaw() = RawEventData(
                eventType = EventType.APP_FOREGROUND,
                packageName = "com.example",
                className = ".MainActivity",
                summary = "Test event"
            )
        }
        collector.emit(source)
        assertNotNull("Event should be dispatched", received)
        assertEquals(EventType.APP_FOREGROUND, received!!.eventType)
        assertEquals("com.example", received!!.packageName)
    }

    @Test
    fun `emit includes navigation context`() {
        var received: RawEvent? = null
        val pipeline = object : EventPipeline {
            override fun register(handler: EventHandler) {}
            override fun unregister(handlerId: String) {}
            override fun dispatch(event: RawEvent) { received = event }
        }
        val tracker = NavigationContextTracker()
        tracker.onScreenChanged(ContextFrame(100L, "com.a", "", "首页"))
        tracker.onScreenChanged(ContextFrame(200L, "com.b", "", "详情页", source = "点击商品"))
        val collector = RawEventCollector(pipeline, tracker)
        collector.emit(object : EventSource {
            override fun collectRaw() = RawEventData(eventType = EventType.ACTIVITY_CHANGE)
        })
        assertEquals(2, received!!.navigationContext.size)
        assertEquals("详情页", received!!.navigationContext.last().screenLabel)
    }
}
