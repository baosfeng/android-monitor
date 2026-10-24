package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleEngine.kt 的规则求值；职责：按「规则 → 组 → 匹配项」顺序求值、Activity 过滤与动作执行，
// 命中即返回（顺序有语义：内置/订阅规则的书写顺序决定先点哪个）。

import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchAction
import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.util.DebugConfig

/** 一次命中：结果 + 用于确认复核的匹配规则 */
internal class SkipHit(val result: SkipRuleEngine.SkipResult, val matchRule: MatchRule)

/**
 * 规则求值器。
 *
 * 求值顺序（与原实现一致，不能调换）：
 * 1. 同一 App 的规则按「应用规则优先、全局规则其后，再按 id」排序；
 * 2. 规则内按 `group.order` 升序；
 * 3. 组内按 `rules` 原始顺序，第一条可执行的即返回。
 */
internal class SkipRuleMatcher(
    private val ruleManager: SkipRuleManager,
    private val gate: SkipRuleGate,
) {

    /**
     * 依次尝试匹配规则；返回命中的结果。
     *
     * @param currentClassName 当前 Activity（供 activityIds 过滤）
     */
    fun match(
        packageName: String,
        root: AccessibilityNodeInfo,
        now: Long,
        currentClassName: String,
        enterTimeMs: Long,
        extensionGrantedMs: Long,
    ): SkipHit? {
        val rules = ruleManager.findRulesFor(packageName)
            .sortedWith(compareBy({ if (it.isGlobal) 1 else 0 }, { it.id }))
        for (rule in rules) {
            val hit = matchRule(rule, packageName, root, now, currentClassName, enterTimeMs, extensionGrantedMs)
                ?: continue
            return hit
        }
        DebugConfig.log("SkipEngine: 未命中任何规则 " + packageName + " / " + currentClassName)
        return null
    }

    private fun matchRule(
        rule: SkipRule,
        packageName: String,
        root: AccessibilityNodeInfo,
        now: Long,
        currentClassName: String,
        enterTimeMs: Long,
        extensionGrantedMs: Long,
    ): SkipHit? {
        for (group in rule.groups.sortedBy { it.order }) {
            if (!group.enabled || !activityMatches(group, packageName, currentClassName)) continue
            for (matchRule in group.rules) {
                val selector = applyMatchRule(
                    rule, group, matchRule, root, now, enterTimeMs, extensionGrantedMs
                ) ?: continue
                val ruleName = matchRule.name.ifEmpty { group.name }
                val result = SkipRuleEngine.SkipResult(
                    packageName, rule.name + "/" + ruleName, selector, matchRule.action.value
                )
                return SkipHit(result, matchRule)
            }
        }
        return null
    }

    /** 执行单条匹配规则；返回命中的选择器 */
    private fun applyMatchRule(
        rule: SkipRule,
        group: RuleGroup,
        matchRule: MatchRule,
        root: AccessibilityNodeInfo,
        now: Long,
        enterTimeMs: Long,
        extensionGrantedMs: Long,
    ): String? {
        val key = gate.keyOf(rule.id, group.key, matchRule.key)
        if (!gate.allowed(rule.id, group, matchRule, now, enterTimeMs, extensionGrantedMs)) return null
        if (matchRule.action == MatchAction.NONE) return null
        if (RuleNodeFinder.anySelectorMatches(matchRule.excludeMatches, matchRule.fastQuery, root)) return null
        val target = RuleNodeFinder.findTarget(matchRule, root) ?: return null
        if (!gate.delaySatisfied(matchRule, key, now) || !gate.actionDelaySatisfied(matchRule, key, now)) {
            NodeMatcher.recycleAll(target.nodes)
            return null
        }
        val executed = try {
            ActionExecutor.perform(target.node, matchRule.action, matchRule.position)
        } finally {
            NodeMatcher.recycleAll(target.nodes)
        }
        if (!executed) {
            DebugConfig.log("SkipEngine: 动作执行失败 " + key + " → " + target.selector)
            return null
        }
        gate.record(rule.id, group, matchRule, now)
        return target.selector
    }

    /** Activity 是否匹配规则组（前缀匹配，`.` 开头自动补包名；exclude 优先） */
    private fun activityMatches(group: RuleGroup, packageName: String, currentClassName: String): Boolean {
        val className = currentClassName
        if (className.isEmpty()) return group.excludeActivityIds.isEmpty()
        if (group.excludeActivityIds.any { matchesActivity(it, className, packageName) }) return false
        if (group.activityIds.isEmpty()) return true
        return group.activityIds.any { matchesActivity(it, className, packageName) }
    }

    private fun matchesActivity(pattern: String, className: String, packageName: String): Boolean {
        val full = if (pattern.startsWith(".")) packageName + pattern else pattern
        return className == full || className.startsWith(full)
    }
}
