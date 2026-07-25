package com.bsfeng.monitor.pipeline.handler

import com.bsfeng.monitor.domain.model.EventType
import com.bsfeng.monitor.pipeline.RawEvent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogHandlerTest {

    @Test
    fun `handle does not throw`() = runTest {
        val handler = LogHandler()
        val event = RawEvent(
            eventType = EventType.APP_FOREGROUND,
            deviceId = "test",
            summary = "Test log event"
        )
        // 只需验证不抛出异常
        handler.handle(event)
    }
}
