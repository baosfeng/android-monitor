package com.bsfeng.monitor.quota

import android.content.Context
import com.bsfeng.monitor.domain.quota.QuotaInstances
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 账号实例清单的落盘行为（Robolectric + 真 SharedPreferences）。
 *
 * 盯住三件事：
 * 1. 空清单 → 按注册表合成默认单实例（**零迁移**：第一次进 App 不写盘也正常工作）；
 * 2. 新增 / 改名 / 删除都落到盘上，重建后仍在（否则切个 Tab 账号就没了）；
 * 3. 删除会重排「只剩一个账号」的边界，但**其余账号的 id 不变**（重排会让凭据串家）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaInstanceStoreTest {

    private lateinit var context: Context
    private val types = listOf("ollama", "deepseek")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = QuotaInstanceStore(context, types)

    @Test
    fun `空清单按注册表合成默认单实例且 id 等于类型 id`() {
        val instances = store().instances()

        assertEquals(listOf("ollama", "deepseek"), instances.map { it.instanceId })
        assertEquals(listOf("ollama", "deepseek"), instances.map { it.providerTypeId })
    }

    @Test
    fun `新增账号落盘且重建后仍在`() {
        val first = store()
        val created = first.add("ollama")
        assertNotNull(created)
        assertEquals("ollama#2", created?.instanceId)

        val reopened = store()
        assertEquals(
            listOf("ollama", "ollama#2", "deepseek"),
            reopened.instances().map { it.instanceId },
        )
    }

    @Test
    fun `新增账号后第一个账号的 id 不变（零迁移）`() {
        val first = store()
        first.add("ollama")
        first.add("ollama")

        assertEquals(
            listOf("ollama", "ollama#2", "ollama#3", "deepseek"),
            first.instances().map { it.instanceId },
        )
    }

    @Test
    fun `未知类型不接受新增`() {
        assertNull("注册表里没有的类型不该长出账号", store().add("ghost"))
    }

    @Test
    fun `改名落盘且空串回默认名`() {
        val first = store()
        first.rename("deepseek", "工作账号")
        assertEquals("工作账号", store().instances().first { it.instanceId == "deepseek" }.label)

        first.rename("deepseek", "")
        assertEquals("", store().instances().first { it.instanceId == "deepseek" }.label)
    }

    @Test
    fun `删除账号后其余账号 id 不重排`() {
        val first = store()
        first.add("ollama")
        first.add("ollama")
        // 删中间那个：剩下的 ollama#3 不能被压成 ollama#2，否则凭据会串到刚删掉的键上
        assertNotNull(first.remove("ollama#2"))

        assertEquals(
            listOf("ollama", "ollama#3", "deepseek"),
            first.instances().map { it.instanceId },
        )
    }

    @Test
    fun `只剩一个账号时拒绝删除`() {
        val first = store()

        assertNull("没有「某类型零账号」这个状态", first.remove("deepseek"))
        assertEquals(listOf("ollama", "deepseek"), first.instances().map { it.instanceId })
        assertTrue(first.isOnlyAccount("deepseek"))
        assertTrue("未知实例按唯一处理，宁可不可删", first.isOnlyAccount("ghost"))
    }

    @Test
    fun `有第二个账号时该类型不再算唯一账号`() {
        val first = store()
        first.add("deepseek")

        assertTrue(!first.isOnlyAccount("deepseek"))
        assertEquals(
            "删除入口的判据与多账号判据同源",
            false,
            QuotaInstances.normalize(first.instances(), types).count { it.providerTypeId == "deepseek" } <= 1,
        )
    }

    @Test
    fun `坏数据回落成空清单再补默认实例`() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, "{not-an-array").commit()

        assertEquals(listOf("ollama", "deepseek"), store().instances().map { it.instanceId })
    }

    private companion object {
        const val PREFS = "quota_instances"
        const val KEY = "instances"
    }
}
