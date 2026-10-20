package com.bsfeng.monitor.finance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [FinanceMutationSignal] 纯逻辑测试。
 *
 * 验收的是「UI 通知」这条链路的地基：版本单调递增、订阅者能收到、并发写入不丢更新
 * （并发丢更新会让自动记账落库后 UI 静默不刷新，正是本次真机问题的形态）。
 */
class FinanceMutationSignalTest {

    @Test
    fun `初始版本为 0`() {
        val signal = FinanceMutationSignal()
        assertEquals(0L, signal.current())
        assertEquals(0L, signal.version.value)
    }

    @Test
    fun `每次 bump 加一并返回新版本`() {
        val signal = FinanceMutationSignal()
        assertEquals(1L, signal.bump())
        assertEquals(2L, signal.bump())
        assertEquals("同步快照应与 Flow 当前值一致", 2L, signal.current())
        assertEquals(2L, signal.version.value)
    }

    @Test
    fun `订阅者在 bump 后收到新版本`() = runBlocking {
        val signal = FinanceMutationSignal()
        val seen = CopyOnWriteArrayList<Long>()
        val job = launch(Dispatchers.Default) {
            signal.version.collect { seen += it }
        }
        yield()
        signal.bump()
        signal.bump()
        // StateFlow 允许合并中间值，这里只要求「最终能收到最新版本」
        withTimeout(5_000) {
            while (seen.lastOrNull() != 2L) yield()
        }
        job.cancelAndJoin()
        assertEquals(2L, seen.last())
    }

    @Test
    fun `并发 bump 不丢更新也不倒退`() = runBlocking {
        val signal = FinanceMutationSignal()
        val threads = 8
        val perThread = 200
        withContext(Dispatchers.Default) {
            (1..threads).map {
                async { repeat(perThread) { signal.bump() } }
            }.awaitAll()
        }
        assertEquals((threads * perThread).toLong(), signal.current())
        assertEquals((threads * perThread).toLong(), signal.version.value)
    }

    @Test
    fun `bump 不改变已发布版本的递增语义`() {
        // 反向验证：从「先读后写」的朴素实现（两个线程都会读到相同旧值）看，
        // 这种实现会让版本小于实际写入次数；compareAndSet 循环则保证等于写入次数。
        val signal = FinanceMutationSignal()
        val writes = 100
        repeat(writes) { signal.bump() }
        assertEquals(writes.toLong(), signal.current())
    }
}
