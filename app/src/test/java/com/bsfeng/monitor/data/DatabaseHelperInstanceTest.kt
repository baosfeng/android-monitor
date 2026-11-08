package com.bsfeng.monitor.data

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [DatabaseHelper.getInstance] 的 application 身份隔离与 LRU 淘汰。
 *
 * 回归点：Robolectric 用例之间共享静态；旧实现只认第一次调用，上一个用例排队中的异步落库
 * 会写进下一个用例的共享表（transfer_tasks 实测 6 行 / 期望 1 行）。也不能「换 application
 * 就 close 重建」：并发调用会互相把对方的连接关掉，导致读写分裂到两个连接。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseHelperInstanceTest {

    /** 模拟「另一个 Application 实例」：applicationContext 返回自身，身份与真实 Application 不同。 */
    private class OtherApplication(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `同一 application 两次 getInstance 返回同一实例`() {
        val app = RuntimeEnvironment.getApplication()
        val first = DatabaseHelper.getInstance(app)
        val second = DatabaseHelper.getInstance(app)

        assertSame(first, second)
        assertSame("传 applicationContext 与 Application 等价", first, DatabaseHelper.getInstance(app.applicationContext))
    }

    @Test
    fun `换 application 返回独立实例且不关旧连接`() {
        val app = RuntimeEnvironment.getApplication()
        val old = DatabaseHelper.getInstance(app)
        val oldDb = old.writableDatabase
        assertTrue(oldDb.isOpen)

        val fresh = DatabaseHelper.getInstance(OtherApplication(app))

        assertNotSame("换 Application 必须换一份库连接", old, fresh)
        assertTrue("旧连接不能因为别的上下文调用而被关掉（并发时会读写分裂）", oldDb.isOpen)
    }

    @Test
    fun `切回原 application 在对象存活期间复用同一实例`() {
        val app = RuntimeEnvironment.getApplication()
        val first = DatabaseHelper.getInstance(app)
        DatabaseHelper.getInstance(OtherApplication(app))

        assertSame("持有期间切走再切回，不应重建连接", first, DatabaseHelper.getInstance(app))
    }
}
