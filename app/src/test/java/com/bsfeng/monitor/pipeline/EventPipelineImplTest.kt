package com.bsfeng.monitor.pipeline

import com.bsfeng.monitor.event.EventType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EventPipelineImplTest {

    @Test
    fun `register and dispatch to handler`() = runTest {
        val pipeline = EventPipelineImpl()
        var handled = false
        val handler = object : EventHandler {
            override val id = "test"
            override suspend fun handle(event: RawEvent) { handled = true }
        }
        pipeline.register(handler)
        pipeline.dispatch(RawEvent(eventType = EventType.APP_FOREGROUND, deviceId = "test-device"))
        Thread.sleep(100) // 等待协程执行
        assertTrue("Handler should have been called", handled)
    }

    @Test
    fun `unregister removes handler`() = runTest {
        val pipeline = EventPipelineImpl()
        var callCount = 0
        val handler = object : EventHandler {
            override val id = "test"
            override suspend fun handle(event: RawEvent) { callCount++ }
        }
        pipeline.register(handler)
        pipeline.unregister("test")
        pipeline.dispatch(RawEvent(eventType = EventType.APP_FOREGROUND, deviceId = "test-device"))
        Thread.sleep(100)
        assertEquals("Handler should NOT have been called", 0, callCount)
    }

    @Test
    fun `one handler failure does not affect others`() = runTest {
        val pipeline = EventPipelineImpl()
        var otherCalled = false
        pipeline.register(object : EventHandler {
            override val id = "failing"
            override suspend fun handle(event: RawEvent) { throw RuntimeException("fail") }
        })
        pipeline.register(object : EventHandler {
            override val id = "working"
            override suspend fun handle(event: RawEvent) { otherCalled = true }
        })
        pipeline.dispatch(RawEvent(eventType = EventType.APP_FOREGROUND, deviceId = "test-device"))
        Thread.sleep(100)
        assertTrue("Working handler should still be called", otherCalled)
    }
}
