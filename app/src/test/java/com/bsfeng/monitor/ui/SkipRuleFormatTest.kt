package com.bsfeng.monitor.ui

import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则明细文案（规则页展开后的摘要）单元测试。
 *
 * 这些函数是排查规则的入口，写错会把「闸门没生效」这类问题藏起来，因此把格式固定住。
 */
class SkipRuleFormatTest {

    @Test
    fun `组摘要包含时间闸门与界面范围`() {
        val group = RuleGroup(
            key = 0,
            name = "开屏广告",
            matchTime = 8_000L,
            actionMaximum = 1,
            actionCd = 1_000L,
            resetMatch = ResetMatch.APP,
            activityIds = listOf("com.demo.MainActivity"),
        )
        val text = groupScopeText(group)
        assertTrue(text, text.contains("matchTime=8s"))
        assertTrue(text, text.contains("actionMaximum=1"))
        assertTrue(text, text.contains("actionCd=1000ms"))
        assertTrue(text, text.contains("reset=app"))
        assertTrue(text, text.contains("界面=com.demo.MainActivity"))
    }

    @Test
    fun `未限定界面与不限时长时的文案`() {
        val text = groupScopeText(RuleGroup(key = 0, matchTime = 0L))
        assertTrue(text, text.contains("matchTime=不限"))
        assertTrue(text, text.contains("界面=全部"))
    }

    @Test
    fun `排除界面会单独列出`() {
        val text = groupScopeText(RuleGroup(key = 0, excludeActivityIds = listOf("com.demo.SettingsActivity")))
        assertTrue(text, text.contains("排除界面=com.demo.SettingsActivity"))
    }

    @Test
    fun `规则摘要无非常规闸门时只显示名称与动作`() {
        val text = matchRuleText(MatchRule(key = 3, name = "跳过文本", action = MatchAction.CLICK))
        assertEquals("#3 跳过文本 · click", text)
    }

    @Test
    fun `规则摘要列出设置的闸门`() {
        val text = matchRuleText(
            MatchRule(
                key = 1,
                name = "关闭广告",
                action = MatchAction.CLICK_CENTER,
                matchDelay = 500L,
                actionDelay = 300L,
                matchTime = 5_000L,
                actionMaximum = 2,
                actionCd = 800L,
                actionCdKey = 0,
                actionMaximumKey = 0,
                preKeys = listOf(0, 1),
                fastQuery = false,
            )
        )
        listOf(
            "matchDelay=500ms", "actionDelay=300ms", "matchTime=5s", "actionMaximum=2",
            "actionCd=800ms", "actionMaximumKey=0", "actionCdKey=0", "preKeys=0,1", "fastQuery=false"
        ).forEach { guard -> assertTrue(guard + " 应出现在 " + text, text.contains(guard)) }
    }

    @Test
    fun `未命名规则有兜底文案`() {
        assertEquals("#0 未命名 · click", matchRuleText(MatchRule(key = 0)))
    }

    @Test
    fun `选择器按类型分行`() {
        val matchRule = MatchRule(
            key = 0,
            matches = listOf("[text=\"A\"]", "[text=\"B\"]"),
            anyMatches = listOf("[vid=\"skip\"]"),
            excludeMatches = listOf("[text=\"关闭\"]"),
        )
        assertEquals(
            listOf(
                "matches: [text=\"A\"]",
                "matches: [text=\"B\"]",
                "any: [vid=\"skip\"]",
                "exclude: [text=\"关闭\"]"
            ),
            selectorLines(matchRule)
        )
    }

    @Test
    fun `没有选择器时给出提示而不是空列表`() {
        assertEquals(listOf("（无选择器）"), selectorLines(MatchRule(key = 0)))
    }
}
