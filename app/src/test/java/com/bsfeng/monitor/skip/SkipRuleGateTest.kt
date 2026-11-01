package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.ResetMatch
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则执行闸门（纯 JVM）：窗口 / 执行次数 / 冷却 / preKeys 顺序 / 延迟复核 / Activity 作用域重置。
 *
 * 键空间 ruleId#groupKey#ruleKey，共享键（actionMaximumKey / actionCdKey）让多条规则共用一个槽位。
 */
class SkipRuleGateTest {

    private val gate = SkipRuleGate()
    private val group = RuleGroup(key = 7, matchTime = 10_000L, actionMaximum = 1, actionCd = 1_000L)
    private val rule = MatchRule(key = 1)

    @Test
    fun `窗口内允许执行窗口外拒绝`() {
        assertTrue(gate.allowed("app", group, rule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        assertTrue(gate.allowed("app", group, rule, now = 10_000L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        assertFalse(gate.allowed("app", group, rule, now = 10_001L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        // 弱网顺延把额度加进宽限，窗口跟着放宽
        assertTrue(gate.allowed("app", group, rule, now = 14_000L, enterTimeMs = 0L, extensionGrantedMs = 4_000L))
        // matchTime <= 0 表示不限制窗口
        val unlimited = group.copy(matchTime = 0L)
        assertTrue(gate.allowed("app", unlimited, rule, now = 999_999L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `执行次数达上限后拒绝`() {
        assertTrue(gate.allowed("app", group, rule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        gate.record("app", group, rule, now = 0L)
        assertFalse(gate.allowed("app", group, rule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        // actionMaximum <= 0 = 不限次数（冷却同组默认 1000ms，这里一并关掉只看次数）
        val unlimited = group.copy(actionMaximum = 0, actionCd = 0L)
        assertTrue(gate.allowed("app", unlimited, rule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `冷却期内拒绝到期后放行`() {
        gate.record("app", group, rule, now = 0L)
        assertFalse(gate.allowed("app", group, rule, now = 500L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        val noLimit = group.copy(actionMaximum = 0)
        assertTrue(gate.allowed("app", noLimit, rule, now = 1_000L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `共享键让多条规则互相占位`() {
        val first = MatchRule(key = 1, actionMaximumKey = 99, actionCdKey = 99)
        val second = MatchRule(key = 2, actionMaximumKey = 99, actionCdKey = 99)
        assertTrue(gate.allowed("app", group, first, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        gate.record("app", group, first, now = 0L)
        // 第二条规则共享计数与冷却槽：次数已满、冷却未到期
        assertFalse(gate.allowed("app", group, second, now = 100L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `preKeys 要求前置规则刚执行过`() {
        val chained = MatchRule(key = 2, preKeys = listOf(1))
        assertFalse(gate.allowed("app", group, chained, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        gate.record("app", group, rule, now = 0L)
        val noCd = group.copy(actionMaximum = 0, actionCd = 0L)
        assertTrue(gate.allowed("app", noCd, chained, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `actionDelay 首次挂起到期且仍命中才放行`() {
        val delayed = MatchRule(key = 3, actionDelay = 300L)
        assertFalse(gate.actionDelaySatisfied(delayed, "k", now = 0L))
        assertFalse(gate.actionDelaySatisfied(delayed, "k", now = 299L))
        assertTrue(gate.actionDelaySatisfied(delayed, "k", now = 300L))
        // 放行后清掉确认时间，下一次重新等
        assertFalse(gate.actionDelaySatisfied(delayed, "k", now = 301L))
        // 无延迟直接放行
        assertTrue(gate.actionDelaySatisfied(MatchRule(key = 4), "k", now = 0L))
    }

    @Test
    fun `matchDelay 挂起到期后放行且不再重置`() {
        val delayed = MatchRule(key = 5, matchDelay = 200L)
        assertFalse(gate.delaySatisfied(delayed, "k", now = 0L))
        assertFalse(gate.delaySatisfied(delayed, "k", now = 199L))
        assertTrue(gate.delaySatisfied(delayed, "k", now = 200L))
        assertTrue(gate.delaySatisfied(delayed, "k", now = 201L))
        assertTrue(gate.delaySatisfied(MatchRule(key = 6), "k", now = 0L))
    }

    @Test
    fun `clear 清空全部闸门状态`() {
        gate.record("app", group, rule, now = 0L)
        assertFalse(gate.allowed("app", group, rule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        gate.clear()
        assertTrue(gate.allowed("app", group, rule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `Activity 作用域重置只清 ACTIVITY 组的计数`() {
        val appScoped = RuleGroup(key = 7, matchTime = 0L, actionMaximum = 1, actionCd = 0L, resetMatch = ResetMatch.APP)
        val activityScoped = appScoped.copy(key = 8, resetMatch = ResetMatch.ACTIVITY)
        val appRule = MatchRule(key = 1)
        val actRule = MatchRule(key = 2)
        val skip = SkipRule(
            id = "app",
            app = "app",
            name = "app",
            groups = listOf(
                appScoped.copy(rules = listOf(appRule)),
                activityScoped.copy(rules = listOf(actRule)),
            ),
        )
        gate.record("app", appScoped, appRule, now = 0L)
        gate.record("app", activityScoped, actRule, now = 0L)
        gate.resetActivityScoped(listOf(skip))
        // ACTIVITY 作用域恢复可执行；APP 作用域仍占着
        assertTrue(gate.allowed("app", activityScoped, actRule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
        assertFalse(gate.allowed("app", appScoped, appRule, now = 0L, enterTimeMs = 0L, extensionGrantedMs = 0L))
    }

    @Test
    fun `键格式为 ruleId 井号 groupKey 井号 ruleKey`() {
        assertEquals("app#7#1", gate.keyOf("app", 7, 1))
    }
}
