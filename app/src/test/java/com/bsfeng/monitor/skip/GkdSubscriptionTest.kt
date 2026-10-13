package com.bsfeng.monitor.skip

import android.content.Context
import com.bsfeng.monitor.skip.json.Json5Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * GKD 订阅拉取的纯逻辑测试（Robolectric：解析链路用到 org.json）。
 *
 * 只覆盖不依赖网络的分支：https 强制、版本号读取、JSON5 → 规则、解析失败降级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GkdSubscriptionTest {

    private lateinit var manager: SkipRuleManager

    @Before
    fun setup() {
        manager = SkipRuleManager(RuntimeEnvironment.getApplication() as Context)
    }

    @Test
    fun `非 https 地址直接失败且不发起请求`() {
        val outcome = GkdSubscription.update(manager, "http://example.com/gkd.json5")
        assertTrue(outcome is GkdSubscription.Outcome.Failed)
        assertEquals("仅支持 https 订阅地址", (outcome as GkdSubscription.Outcome.Failed).reason)
    }

    @Test
    fun `读取订阅版本号`() {
        assertEquals(67L, GkdSubscription.readVersion("{id:666, version:67, apps:[]}"))
        // 缺少 version 字段时返回 null（调用方按 0 处理）
        assertNull(GkdSubscription.readVersion("{id:1, name:'x'}"))
    }

    @Test
    fun `解析订阅文本生成规则`() {
        val text = """
            {
              id: 666, name: 'X', version: 3,
              globalGroups: [{ key: 0, name: '开屏广告', matchTime: 10000, actionMaximum: 1,
                rules: [{ key: 0, matches: '[text*="跳过"]' }] }],
              apps: [{ id: 'com.demo.app', name: 'Demo', groups: [
                { key: 0, name: '开屏广告', activityIds: ['com.demo.app.MainActivity'],
                  rules: [{ key: 0, matches: '[vid="skip"]' }] }] }]
            }
        """.trimIndent()
        val rules = GkdSubscription.parseRules(text)
        assertEquals(2, rules.size)
        assertTrue(rules.any { it.isGlobal })
        assertTrue(rules.any { it.app == "com.demo.app" })
        assertEquals(3L, GkdSubscription.readVersion(text))
    }

    @Test
    fun `解析失败返回空列表`() {
        assertTrue(GkdSubscription.parseRules("{ 非法 json5 ").isEmpty())
        assertTrue(GkdSubscription.parseRules("[]").isEmpty())
    }

    // ==================== 定时检查（不发网络请求的分支） ====================

    @Test
    fun `未订阅时自动检查直接返回`() {
        manager.resetToBuiltIn()
        assertTrue(GkdSubscription.autoCheck(manager, now = Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `未到检查周期时不重复拉取`() {
        manager.saveSubscription(
            url = "https://example.com/gkd.json5",
            version = 5L,
            rules = emptyList()
        )
        manager.markSubscriptionChecked(1_000L)
        assertTrue("距上次检查不足 24 小时应跳过", GkdSubscription.autoCheck(manager, now = 2_000L).isEmpty())
        assertEquals(1_000L, manager.lastSubscriptionCheckAt())
    }

    // ==================== 两级订阅地址解析 ====================

    @Test
    fun `相对 updateUrl 按订阅地址目录解析`() {
        val root = Json5Parser.parse("{id:1, version:2, updateUrl:'./gkd.json5'}")
        val resolved = GkdSubscription.resolveUpdateUrl(root, "https://cdn.example.com/dir/ver.json5")
        assertEquals("https://cdn.example.com/dir/gkd.json5", resolved)
    }

    @Test
    fun `绝对 https updateUrl 原样返回`() {
        val root = Json5Parser.parse("{checkUpdateUrl:'https://cdn.example.com/a/gkd.json5'}")
        assertEquals(
            "https://cdn.example.com/a/gkd.json5",
            GkdSubscription.resolveUpdateUrl(root, "https://cdn.example.com/ver.json5")
        )
    }

    @Test
    fun `http 的 updateUrl 被拒绝`() {
        val root = Json5Parser.parse("{updateUrl:'http://cdn.example.com/gkd.json5'}")
        assertNull(GkdSubscription.resolveUpdateUrl(root, "https://cdn.example.com/ver.json5"))
    }

    @Test
    fun `缺少 updateUrl 返回空`() {
        assertNull(GkdSubscription.resolveUpdateUrl(Json5Parser.parse("{id:1, version:2}"), "https://x/y.json5"))
        assertNull(GkdSubscription.resolveUpdateUrl(null, "https://x/y.json5"))
    }

    @Test
    fun `包含 apps 或 globalGroups 的订阅无需二次跳转`() {
        assertTrue(GkdSubscription.containsRules(Json5Parser.parse("{apps:[]}")))
        assertTrue(GkdSubscription.containsRules(Json5Parser.parse("{globalGroups:[]}")))
        assertFalse(GkdSubscription.containsRules(Json5Parser.parse("{id:1, version:2}")))
        assertFalse(GkdSubscription.containsRules(null))
    }
}
