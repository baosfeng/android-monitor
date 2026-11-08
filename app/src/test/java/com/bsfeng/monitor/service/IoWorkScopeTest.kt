package com.bsfeng.monitor.service

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有生命周期宿主的 IO 协程容器（A11）。
 *
 * 回归点：旧代码四处现场 new `CoroutineScope(Dispatchers.IO + SupervisorJob())` 且不持句柄，
 * 服务 onDestroy 后协程仍在写库；这里钉住「能取消」与「取消后不再跑」。
 */
class IoWorkScopeTest {

    @Test
    fun `提交的任务会执行`() = runBlocking {
        val work = IoWorkScope(Dispatchers.Unconfined)
        val ran = AtomicInteger(0)
        work.scope.launch { ran.incrementAndGet() }.join()

        assertEquals(1, ran.get())
        assertTrue(work.isActive)
        work.cancel()
    }

    @Test
    fun `cancel 后 scope 失效且不再执行新任务`() = runBlocking {
        val work = IoWorkScope(Dispatchers.Unconfined)
        work.cancel()

        assertFalse(work.isActive)
        assertFalse(work.scope.isActive)
        val ran = AtomicInteger(0)
        work.scope.launch { ran.incrementAndGet() }.join()
        assertEquals("宿主销毁后提交的任务不得执行", 0, ran.get())
    }

    @Test
    fun `单个子任务异常不影响其它子任务 SupervisorJob`() = runBlocking {
        val work = IoWorkScope(Dispatchers.Unconfined)
        val handler = CoroutineExceptionHandler { _, _ -> }
        work.scope.launch(handler) { throw IllegalStateException("模拟写库失败") }
        val ran = AtomicInteger(0)
        work.scope.launch { ran.incrementAndGet() }.join()

        assertEquals("SupervisorJob 下同批任务互不影响", 1, ran.get())
        work.cancel()
    }
}
