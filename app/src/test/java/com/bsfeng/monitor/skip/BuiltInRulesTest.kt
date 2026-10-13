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
}
