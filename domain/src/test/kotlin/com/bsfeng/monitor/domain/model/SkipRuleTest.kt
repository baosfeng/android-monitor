package com.bsfeng.monitor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRuleTest {

    @Test
    fun `isGlobal`() {
        val global = SkipRule(id = "g", app = SkipRule.GLOBAL_APP, name = "g")
        val app = SkipRule(id = "a", app = "com.x", name = "a")
        assertTrue(global.isGlobal)
        assertFalse(app.isGlobal)
    }

    @Test
    fun `MatchAction from`() {
        assertEquals(MatchAction.CLICK, MatchAction.from(null))
        assertEquals(MatchAction.BACK, MatchAction.from("back"))
        assertEquals(MatchAction.CLICK_CENTER, MatchAction.from("clickCenter"))
        assertEquals(MatchAction.CLICK, MatchAction.from("nope"))
        assertEquals("click", MatchAction.CLICK.value)
    }

    @Test
    fun `ResetMatch from`() {
        assertEquals(ResetMatch.ACTIVITY, ResetMatch.from(null))
        assertEquals(ResetMatch.APP, ResetMatch.from("app"))
        assertEquals(ResetMatch.MATCH, ResetMatch.from("match"))
        assertEquals(ResetMatch.ACTIVITY, ResetMatch.from("nope"))
    }

    @Test
    fun `data classes equality and defaults`() {
        val rule = SkipRule(id = "r", app = "a", name = "n")
        assertEquals(listOf<String>(), rule.excludedApps)
        assertEquals(listOf<RuleGroup>(), rule.groups)
        assertTrue(rule.enabled)

        val group = RuleGroup()
        assertEquals(10_000L, group.matchTime)
        assertEquals(1, group.actionMaximum)
        assertEquals(1_000L, group.actionCd)
        assertEquals(ResetMatch.APP, group.resetMatch)

        val mr = MatchRule()
        assertEquals(MatchAction.CLICK, mr.action)
        assertTrue(mr.fastQuery)
        org.junit.Assert.assertNull(mr.position)

        val cp = ClickPosition(0.25f, 0.75f)
        assertEquals(0.25f, cp.x)
        assertEquals(cp, ClickPosition(0.25f, 0.75f))
    }
}
