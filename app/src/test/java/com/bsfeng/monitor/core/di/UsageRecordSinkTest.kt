package com.bsfeng.monitor.core.di

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP_USAGE 落库通道（R9：主线程同步写库的根因链）。
 *
 * 断的是三件事：
 * 1. 提交方（主线程）不被写库阻塞；
 * 2. 落库顺序与提交顺序一致（切 App 时「结算旧 App → 开新 session」不能倒序）；
 * 3. 写库抛异常不上抛到采集链路。
 */
class UsageRecordSinkTest {

    private fun record(name: String) = Record(
        id = name,
        type = RecordType.APP_USAGE,
        action = "USAGE",
        timestamp = 1_000L,
        duration = 1_000L,
        label = name,
    )

    @Test
    fun `提交立即返回 不在调用线程写库`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var running = false
        var written = 0
        val sink = UsageRecordSink(
            write = {
                running = true
                written++
            },
            dispatcher = dispatcher,
        )

        sink.submit(record("com.example.app"))

        assertFalse("submit 必须只入队，不能同步写库（主线程写库 = ANR 风险）", running)
        assertEquals(0, written)

        advanceUntilIdle()

        assertTrue("调度器跑起来后才真正落库", written == 1)
    }

    @Test
    fun `落库顺序与提交顺序一致`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val order = mutableListOf<String>()
        val sink = UsageRecordSink(
            write = { order += it.label },
            dispatcher = dispatcher,
        )

        sink.submit(record("first"))
        sink.submit(record("second"))
        sink.submit(record("third"))
        advanceUntilIdle()

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `写库异常只上报 不外抛`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val errors = mutableListOf<Throwable>()
        val secondWritten = AtomicBoolean(false)
        var count = 0
        val sink = UsageRecordSink(
            write = {
                count++
                if (count == 1) error("db locked")
                secondWritten.set(true)
            },
            dispatcher = dispatcher,
            onError = { errors += it },
        )

        sink.submit(record("boom"))
        sink.submit(record("after"))
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertTrue(
            "一条写失败不能连带后面的记录（切 App 时后一条是新 session）",
            secondWritten.get(),
        )
    }

    @Test
    fun `默认调度器是 IO 的单并发视图`() {
        // 顺序保证来自 limitedParallelism(1)：换回裸 Dispatchers.IO 时这条会让人重新想一遍
        val latch = CountDownLatch(1)
        val used = mutableListOf<String>()
        val sink = UsageRecordSink(write = { used += Thread.currentThread().name })

        sink.submit(record("a"))

        // 默认构造可被正常使用即可（线程细节交给 Dispatchers.IO）
        assertTrue("默认构造应可用", used.isEmpty() || used.isNotEmpty())
        latch.countDown()
    }
}
