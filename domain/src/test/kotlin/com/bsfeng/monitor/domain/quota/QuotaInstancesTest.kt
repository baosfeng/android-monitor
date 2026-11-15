package com.bsfeng.monitor.domain.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号实例清单的纯逻辑：零迁移的默认 id、新实例分配、规范化与展示名。
 *
 * 这些判据错了不会崩，只会「两个账号共用一个键」——凭据 / 快照 / 预警互相覆盖，
 * 而且症状是「数据不更新」这种最难归因的形态，所以必须由单测钉死。
 */
class QuotaInstancesTest {

    private val types = listOf("ollama", "deepseek", "opencode-go", "opendesign")

    @Test
    fun `默认单实例的 instanceId 等于类型 id（零迁移）`() {
        types.forEach { type ->
            assertEquals(type, QuotaInstances.defaultInstance(type).instanceId)
            assertEquals(type, QuotaInstances.defaultInstance(type).providerTypeId)
        }
        assertEquals(
            types,
            QuotaInstances.defaults(types).map { it.instanceId },
        )
    }

    @Test
    fun `空清单按注册表补齐默认实例`() {
        val normalized = QuotaInstances.normalize(emptyList(), types)
        assertEquals(types, normalized.map { it.instanceId })
        assertEquals(types, normalized.map { it.providerTypeId })
    }

    @Test
    fun `已落盘清单不会丢掉缺失的类型`() {
        // 升级新增 provider 时：清单里没有它 → 直接补默认实例，不需要迁移偏好
        val stored = listOf(QuotaInstance("ollama", "ollama"))
        val normalized = QuotaInstances.normalize(stored, listOf("ollama", "deepseek"))
        assertEquals(listOf("ollama", "deepseek"), normalized.map { it.instanceId })
    }

    @Test
    fun `新实例 id 从第二号起顺延且跳过已占用`() {
        assertEquals("ollama#2", QuotaInstances.nextInstanceId("ollama", listOf("ollama")))
        assertEquals(
            "ollama#3",
            QuotaInstances.nextInstanceId("ollama", listOf("ollama", "ollama#2")),
        )
    }

    @Test
    fun `未知类型的脏数据被丢弃`() {
        val stored = listOf(
            QuotaInstance("ollama", "ollama"),
            QuotaInstance("ghost#2", "ghost"),
            QuotaInstance("ollama", "ollama"), // 重复 id
        )
        val normalized = QuotaInstances.normalize(stored, listOf("ollama"))
        assertEquals(listOf("ollama"), normalized.map { it.instanceId })
    }

    @Test
    fun `多账号时行标题退回账号序号、单账号时用类型名`() {
        val first = QuotaInstances.defaultInstance("deepseek")
        val second = QuotaInstance("deepseek#2", "deepseek")

        assertEquals("DeepSeek", QuotaInstances.accountName(first, "DeepSeek", multiAccount = false))
        assertEquals("账号 1", QuotaInstances.accountName(first, "DeepSeek", multiAccount = true))
        assertEquals("账号 2", QuotaInstances.accountName(second, "DeepSeek", multiAccount = true))
    }

    @Test
    fun `改过名的账号优先用名字`() {
        val renamed = QuotaInstance("deepseek#2", "deepseek", label = "工作账号")
        assertEquals("工作账号", QuotaInstances.accountName(renamed, "DeepSeek", multiAccount = true))
        assertEquals(
            "DeepSeek · 工作账号",
            QuotaInstances.displayName(renamed, "DeepSeek", multiAccount = true),
        )
    }

    @Test
    fun `单账号的展示名就是类型名`() {
        val only = QuotaInstances.defaultInstance("ollama")
        assertEquals("Ollama", QuotaInstances.displayName(only, "Ollama", multiAccount = false))
    }

    @Test
    fun `多账号的展示名带账号名且互不相同`() {
        val first = QuotaInstances.defaultInstance("deepseek")
        val second = QuotaInstance("deepseek#2", "deepseek")
        val a = QuotaInstances.displayName(first, "DeepSeek", multiAccount = true)
        val b = QuotaInstances.displayName(second, "DeepSeek", multiAccount = true)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("DeepSeek · "))
        assertTrue(b.startsWith("DeepSeek · "))
    }

    @Test
    fun `实例序号解析兼容历史 id`() {
        assertEquals(1, instanceSeq("ollama", "ollama"))
        assertEquals(2, instanceSeq("ollama#2", "ollama"))
        assertEquals(7, instanceSeq("ollama#7", "ollama"))
        // 脏 id：后缀不是数字 → 按第一实例处理，不抛异常
        assertEquals(1, instanceSeq("ollama#x", "ollama"))
    }
}
