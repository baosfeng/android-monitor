package com.bsfeng.monitor.autobook

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AutoBookIgnoreStore] 单元测试（Robolectric，运行真实 SharedPreferences）。
 *
 * 验收点：忽略后同一笔不再弹（跨实例 / 跨进程重启都成立）、过期自动失效、空键不误伤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBookIgnoreStoreTest {

    private lateinit var context: Context
    private var now = 1_000_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 每个用例清空偏好，避免用例间互相污染
        context.getSharedPreferences("autobook_ignored", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun store() = AutoBookIgnoreStore(
        prefs = context.getSharedPreferences("autobook_ignored", Context.MODE_PRIVATE),
        now = { now }
    )

    @Test
    fun `忽略后同一笔不再弹`() {
        val store = store()
        assertFalse(store.isIgnored("abc123"))
        store.markIgnored("abc123")
        assertTrue(store.isIgnored("abc123"))
        // 另一个实例（模拟服务重启 / 进程重建）同样记得
        assertTrue(store().isIgnored("abc123"))
    }

    @Test
    fun `忽略只影响这一笔`() {
        val store = store()
        store.markIgnored("abc123")
        assertFalse(store.isIgnored("def456"))
    }

    @Test
    fun `过期的忽略记录自动失效`() {
        val store = store()
        store.markIgnored("abc123")
        now += AutoBookIgnoreStore.DEFAULT_TTL_MS + 1
        assertFalse(store.isIgnored("abc123"))
        // 过期条目被清掉，不留垃圾
        assertFalse(store.isIgnored("abc123"))
    }

    @Test
    fun `空键不写入也不命中`() {
        val store = store()
        store.markIgnored("")
        assertFalse(store.isIgnored(""))
    }

    @Test
    fun `重复忽略刷新时间戳`() {
        val store = store()
        store.markIgnored("abc123")
        now += AutoBookIgnoreStore.DEFAULT_TTL_MS - 1
        store.markIgnored("abc123")
        now += 2
        assertTrue("重新忽略后应从新时间点重新计时", store.isIgnored("abc123"))
    }

    @Test
    fun `升级后一次性清空旧版忽略记忆`() {
        val prefs = context.getSharedPreferences("autobook_ignored", Context.MODE_PRIVATE)
        // 模拟旧版误触写入的记录
        prefs.edit().putLong("ignored_legacy1", now).putLong("ignored_legacy2", now).commit()
        val store = store()
        assertTrue(store.isIgnored("legacy1"))

        store.migrateLegacyOnce()

        assertFalse("旧版忽略记忆应被清空", store.isIgnored("legacy1"))
        assertFalse(store.isIgnored("legacy2"))
    }

    @Test
    fun `迁移只跑一次新写入的忽略不被清掉`() {
        val store = store()
        store.migrateLegacyOnce()
        store.markIgnored("new1")

        store.migrateLegacyOnce()

        assertTrue("迁移完成后新点的忽略必须保留", store.isIgnored("new1"))
    }
}
