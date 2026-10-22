package com.bsfeng.monitor.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置规则结构校验（纯 JVM）。
 *
 * 规则写错（选择器语法非法、包名重复、缺少防误触闸门）在真机上只表现为"跳不过"，
 * 因此这里把校验固化成测试，避免规则腐烂。
 */
class BuiltInRulesTest {

    private val rules = BuiltInRules.getRules()

    @Test
    fun `内置规则非空且包含唯一全局规则`() {
        assertTrue(rules.isNotEmpty())
        val globals = rules.filter { it.isGlobal }
        assertEquals(1, globals.size)
        assertEquals(BuiltInRules.GLOBAL_SPLASH_RULE_ID, globals.first().id)
    }

    @Test
    fun `应用规则包名与规则 ID 均唯一`() {
        val apps = rules.filter { !it.isGlobal }.map { it.app }
        assertEquals("应用规则包名重复: " + apps, apps.size, apps.toSet().size)
        val ids = rules.map { it.id }
        assertEquals("规则 ID 重复: " + ids, ids.size, ids.toSet().size)
    }

    @Test
    fun `所有选择器都能被解析器解析`() {
        val failures = mutableListOf<String>()
        rules.forEach { rule ->
            rule.groups.forEach { group ->
                group.rules.forEach { matchRule ->
                    val all = matchRule.matches + matchRule.anyMatches + matchRule.excludeMatches
                    assertTrue("规则 " + rule.id + " 缺少选择器", all.isNotEmpty())
                    all.forEach { expr ->
                        if (SelectorCache.get(expr) == null) failures.add(rule.id + " → " + expr)
                    }
                }
            }
        }
        assertTrue("以下选择器解析失败: " + failures, failures.isEmpty())
    }

    @Test
    fun `全局规则带时间闸门与次数限制`() {
        val global = rules.first { it.isGlobal }
        global.groups.forEach { group ->
            assertTrue("全局规则组必须有 matchTime", group.matchTime > 0)
            assertTrue("全局规则组必须有 actionMaximum", group.actionMaximum > 0)
            assertTrue("全局规则组必须有 actionCd", group.actionCd > 0)
        }
    }

    @Test
    fun `开屏文本规则要求短文本与可见性`() {
        val global = rules.first { it.isGlobal }
        val selectors = global.groups.flatMap { it.rules }.flatMap { it.matches }
        val textRule = selectors.firstOrNull { it.contains("text*=\"跳过\"") }
        assertNotNull("全局规则必须包含跳过文本匹配", textRule)
        assertTrue("跳过文本匹配必须限制文本长度", textRule!!.contains("text.length<"))
        assertTrue("跳过文本匹配必须要求可见", textRule.contains("visibleToUser=true"))
    }

    @Test
    fun `内置规则时间闸门跟随传入的监控窗口`() {
        val custom = BuiltInRules.getRules(5_000L)
        custom.forEach { rule ->
            rule.groups.forEach { group ->
                assertEquals("规则 " + rule.id + " 组 " + group.key + " 的 matchTime 应等于监控窗口",
                    5_000L, group.matchTime)
            }
        }
    }

    @Test
    fun `默认时间闸门与默认监控窗口一致`() {
        val global = rules.first { it.isGlobal }
        global.groups.forEach { assertEquals(SkipConfig.DEFAULT_WATCH_WINDOW_MS, it.matchTime) }
    }

    @Test
    fun `QQ阅读规则限定开屏 Activity 且使用精确选择器`() {
        val rule = rules.firstOrNull { it.id == "qq_reader_splash" }
        assertNotNull("QQ阅读规则应存在（真机快照实测新增）", rule)
        assertTrue("必须限定开屏 Activity，避免在阅读页误点", rule!!.groups.all { it.activityIds.isNotEmpty() })
        val selectors = rule.groups.flatMap { it.rules }.flatMap { it.matches }
        assertTrue("应使用 splash_root 做精确限定：$selectors", selectors.any { it.contains("splash_root") })
        assertTrue("必须保留可见性与短文本约束：$selectors",
            selectors.all { it.contains("visibleToUser=true") && it.contains("text.length<") })
    }

    @Test
    fun `全局排除包名不影响应用规则`() {
        assertTrue(BuiltInRules.GLOBAL_EXCLUDED_PACKAGES.contains("com.bsfeng.monitor"))
        assertFalse(rules.filter { !it.isGlobal }.any { it.app in BuiltInRules.GLOBAL_EXCLUDED_PACKAGES })
    }

    @Test
    fun `微信小程序规则限定小程序宿主 Activity`() {
        val rule = rules.firstOrNull { it.id == "wechat_wxa_splash" }
        assertNotNull("微信小程序开屏规则应存在", rule)
        assertEquals("com.tencent.mm", rule!!.app)
        assertFalse("微信不能进全局排除名单，否则小程序规则一起失效",
            rule.app in BuiltInRules.GLOBAL_EXCLUDED_PACKAGES)
        val activityIds = rule.groups.flatMap { it.activityIds }
        assertTrue("必须用 Activity 圈定小程序宿主：$activityIds",
            activityIds.any { it.endsWith("AppBrandUI") })
        assertTrue("必须覆盖冷启动代理页：$activityIds",
            activityIds.any { it.contains("AppBrandLaunchProxyUI") })
    }

    @Test
    fun `微信小程序规则对齐 GKD 的 actionDelay 与 actionCd`() {
        val rule = rules.first { it.id == "wechat_wxa_splash" }
        val matchRules = rule.groups.flatMap { it.rules }
        val ordered = matchRules.firstOrNull { it.matches.size == 2 }
        assertNotNull("应有「广告标识 + 跳过按钮」的顺序匹配规则", ordered)
        assertEquals("顺序匹配需等广告标识渲染完再点", 800L, ordered!!.actionDelay)
        assertTrue("第一个选择器应匹配广告标识：${ordered.matches}",
            ordered.matches.first().contains("广告"))
        assertTrue("第二个选择器应匹配跳过按钮：${ordered.matches}",
            ordered.matches.last().contains("跳过"))

        val fallback = matchRules.firstOrNull { it.key == 1 }
        assertNotNull("应有兜底短文本跳过规则", fallback)
        assertEquals("兜底规则需要更短的冷却", 500L, fallback!!.actionCd)
        assertTrue("兜底规则应限制文本长度与高度：${fallback.matches}",
            fallback.matches.single().let { it.contains("text.length<") && it.contains("height<") })
    }

    @Test
    fun `内置规则版本号随规则变更递增`() {
        assertEquals("规则内容有变更必须递增版本号，触发本地规则合并", 10, BuiltInRules.BUILT_IN_VERSION)
    }
}
