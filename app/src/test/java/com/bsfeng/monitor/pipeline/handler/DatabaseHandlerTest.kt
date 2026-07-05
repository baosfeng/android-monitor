package com.bsfeng.monitor.pipeline.handler

import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.pipeline.RawEvent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DatabaseHandlerTest {

    @Test
    fun `handle does not throw`() = runTest {
        // DatabaseHandler 依赖 DatabaseHelper（Android SQLite），
        // 在 Robolectric 环境下验证 handle() 不抛出异常
        val context = RuntimeEnvironment.getApplication()
        val db = DatabaseHelper(context)
        val handler = DatabaseHandler(db)
        val event = RawEvent(
            eventType = EventType.APP_FOREGROUND,
            deviceId = "test-device",
            summary = "测试事件"
        )
        // 当前仅验证接口兼容性，实际数据库操作需要更完整的测试设置
        handler.handle(event)
    }
}
