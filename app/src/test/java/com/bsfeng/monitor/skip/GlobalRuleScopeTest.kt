package com.bsfeng.monitor.skip

import android.content.Context
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
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
 * 全局规则作用域测试（Robolectric）。
 *
 * 验证 GKD 订阅里 `globalGroups[].apps[{id, enable:false}]` 语义：
 * 被订阅显式禁用的 App 不得应用该全局规则；内置排除名单与用户停用依旧生效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalRuleScopeTest {

    private lateinit var manager: SkipRuleManager

    @Before
    fun setup() {
        manager = SkipRuleManager(RuntimeEnvironment.getApplication() as Context)
        manager.resetToBuiltIn()
    }

    private fun globalRule(excludedApps: List<String>, id: String = "gkd_global_0") = SkipRule(
        id = id,
        app = SkipRule.GLOBAL_APP,
        name = "全局开屏",
        excludedApps = excludedApps,
        groups = listOf(
            RuleGroup(key = 0, name = "开屏广告", rules = listOf(MatchRule(key = 0, matches = listOf("[text=\"跳过\"]"))))
        )
    )

    @Test
    fun `全局规则按 excludedApps 逐应用过滤`() {
        manager.addRule(globalRule(excludedApps = listOf("com.disabled.app")))

        val forDisabled = manager.findRulesFor("com.disabled.app")
        assertTrue("被禁用应用不得应用全局规则", forDisabled.none { it.id == "gkd_global_0" })

        val forOther = manager.findRulesFor("com.other.app")
        assertTrue("其它应用仍应应用全局规则", forOther.any { it.id == "gkd_global_0" })
    }

    @Test
    fun `内置全局规则对排除名单里的包名不生效`() {
        val excluded = BuiltInRules.GLOBAL_EXCLUDED_PACKAGES.first()
        val rules = manager.findRulesFor(excluded)
        assertTrue("系统 UI/桌面等不得应用内置全局开屏规则", rules.none { it.id == BuiltInRules.GLOBAL_SPLASH_RULE_ID })
    }

    @Test
    fun `停用的全局规则不参与匹配`() {
        manager.setRuleEnabled(BuiltInRules.GLOBAL_SPLASH_RULE_ID, false)
        val rules = manager.findRulesFor("com.other.app")
        assertFalse(rules.any { it.id == BuiltInRules.GLOBAL_SPLASH_RULE_ID })
    }

    @Test
    fun `应用规则优先于全局规则`() {
        manager.addRule(
            SkipRule(
                id = "com.other.app",
                app = "com.other.app",
                name = "示例应用",
                groups = listOf(
                    RuleGroup(key = 0, name = "开屏广告", rules = listOf(MatchRule(key = 0, matches = listOf("[vid=\"skip\"]"))))
                )
            )
        )
        val rules = manager.findRulesFor("com.other.app")
        assertTrue(rules.size >= 2)
        assertFalse("第一条必须是应用规则", rules.first().isGlobal)
        assertEquals("com.other.app", rules.first().id)
    }

    @Test
    fun `订阅规则的 excludedApps 往返无损`() {
        val text = """
            {
              id: 1, name: 'X', version: 1,
              globalGroups: [{ key: 0, name: '开屏广告',
                apps: [{ id: 'com.a', enable: false }, { id: 'com.b', enable: true }],
                rules: [{ key: 0, matches: '[text*="跳过"]' }] }]
            }
        """.trimIndent()
        val rules = GkdSubscription.parseRules(text)
        val global = rules.first { it.isGlobal }
        assertEquals(listOf("com.a"), global.excludedApps)
    }
}
