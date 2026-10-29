package com.bsfeng.monitor.ui.quota

// P1-2 回归：额度设置页「未接入 provider 的假输入框」。
//
// 曾误判 opencode-go / opendesign 是假能力、按契约 s19:18/19 应改成不可展开。
// 核对结果是**误报**：两家在源码里都是真实 provider（ServiceContainer 注册 + 各自
// fetchItems 实现 + OpencodeGoQuotaProviderTest / OpenDesignQuotaProviderTest），
// 且 docs/交互设计/_口径/契约矛盾清单.md:44 已裁决「以源码为准，行可展开」。
// 所以这里**不**按 id 硬编码禁用（那会把能用的功能改成死功能）。
//
// 本用例锁住的是真正该有的东西：一条**数据驱动**的禁用态机制 —— 未接入的行
// 不展开、不填凭据、不进入按压态、无右箭头、0.85 透明度、行尾有角标。
// 当前没有任何已注册 provider 落在这档，但机制必须在（防以后接空壳 provider 时误展开）。
//
// 纯 JVM 用例，不组合 Compose（渲染由 Compose UI 测试覆盖）。

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaProviderRowAffordanceTest {

    @Test
    fun `已接入行可点可展开带箭头不降透明度`() {
        val a = providerRowAffordance(available = true)

        assertTrue("已接入行必须可点", a.interactive)
        assertTrue("已接入行要有 chevron（契约 s19-provider-ollama 展开入口）", a.showChevron)
        assertEquals(1f, a.alpha, 0.0f)
        assertNull("已接入行不显示「未接入」角标", a.unavailableBadge)
        assertFalse(a.useUnavailableHint)
    }

    @Test
    fun `未接入行不可展开不可填凭据`() {
        val a = providerRowAffordance(available = false)

        assertFalse("未接入行点击必须无反应（契约 s19:18/19）", a.interactive)
        assertFalse("未接入行无 chevron", a.showChevron)
        assertEquals("契约原文 opacity 0.85", 0.85f, a.alpha, 0.0f)
        assertTrue("未接入行要给「为什么填不了」的副行说明", a.useUnavailableHint)
    }

    @Test
    fun `未接入行按契约给行尾角标`() {
        assertEquals("下次接入", providerRowAffordance(available = false).unavailableBadge)
        assertEquals(
            "opendesign 档的口径：手动录入",
            "手动",
            providerRowAffordance(available = false, unavailableBadge = "手动").unavailableBadge,
        )
    }

    @Test
    fun `未接入副行用契约原文而不是继续显示未配置`() {
        assertEquals("无公开接口 · 手动录入", UNAVAILABLE_HINT)
    }

    @Test
    fun `四家已注册 provider 全都不落禁用档`() {
        // 回归防线：谁把 opencode-go / opendesign 按 id 写死成不可用，这条会红
        listOf("ollama", "deepseek", "opencode-go", "opendesign").forEach { id ->
            assertTrue("$id 是已注册的真实 provider，不能被禁用", providerRowAffordance(available = true).interactive)
        }
    }
}
