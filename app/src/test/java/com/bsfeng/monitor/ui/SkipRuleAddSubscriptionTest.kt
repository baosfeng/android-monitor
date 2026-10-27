package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s10「新增订阅」的判据（契约 s10-source · 新增订阅行）。
 *
 * 回归的是原实现的两个错：① 不判重（重复地址照样新增）② 新增走了 updateSubscription，
 * 隐式发起网络拉取。判重必须先于任何写盘 / 联网发生，所以单独抽成纯函数来测。
 */
class SkipRuleAddSubscriptionTest {

    private val existing = listOf(
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5",
        "https://example.com/other.json5",
    )

    @Test
    fun `新地址走本地新增`() {
        assertEquals(
            LocalSubscriptionOutcome.ADDED,
            classifyLocalSubscription("https://example.com/new.json5", existing),
        )
    }

    @Test
    fun `与已有源重复时判重`() {
        assertEquals(
            LocalSubscriptionOutcome.DUPLICATE,
            classifyLocalSubscription("https://example.com/other.json5", existing),
        )
    }

    @Test
    fun `前后空格不影响判重`() {
        assertEquals(
            LocalSubscriptionOutcome.DUPLICATE,
            classifyLocalSubscription("  https://example.com/other.json5 ", existing),
        )
    }

    @Test
    fun `空地址不新增`() {
        assertEquals(LocalSubscriptionOutcome.BLANK, classifyLocalSubscription("", existing))
        assertEquals(LocalSubscriptionOutcome.BLANK, classifyLocalSubscription("   ", existing))
    }

    @Test
    fun `非 https 地址不新增`() {
        assertEquals(LocalSubscriptionOutcome.NOT_HTTPS, classifyLocalSubscription("http://example.com/a.json5", existing))
        assertEquals(LocalSubscriptionOutcome.NOT_HTTPS, classifyLocalSubscription("example.com/a.json5", existing))
    }

    @Test
    fun `首个订阅源也能新增`() {
        assertEquals(
            LocalSubscriptionOutcome.ADDED,
            classifyLocalSubscription("https://example.com/new.json5", emptyList()),
        )
    }
}
