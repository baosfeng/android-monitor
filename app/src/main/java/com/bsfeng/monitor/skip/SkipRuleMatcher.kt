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

    /** 点击前判据开关（默认开启；置 false 可对照复现误判，单测也用它隔离旧用例） */
    internal var splashGateEnabled: Boolean = true

    /** 判据兜底宽限窗（毫秒，纯结构候选），默认取 [SkipConfig.SPLASH_WORDING_GRACE_MS] */
    internal var splashGraceMs: Long = SkipConfig.SPLASH_WORDING_GRACE_MS

    /** 纯文本候选的短宽限窗（毫秒），默认取 [SkipConfig.SPLASH_TEXT_ONLY_GRACE_MS] */
    internal var splashTextOnlyGraceMs: Long = SkipConfig.SPLASH_TEXT_ONLY_GRACE_MS

    /** 最近一次命中时判据给出的证据（供引擎落库）；判据关闭时为 null */
    internal var lastEvidence: SplashGate.SplashEvidence? = null
        private set

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
                    rule, group, matchRule, root, now, enterTimeMs, extensionGrantedMs,
                    packageName, currentClassName
                ) ?: continue
                val ruleName = matchRule.name.ifEmpty { group.name }
                val evidence = lastEvidence
                val result = SkipRuleEngine.SkipResult(
                    packageName, rule.name + "/" + ruleName, selector, matchRule.action.value,
                    matchedText = evidence?.matchedText,
                    verdictReason = evidence?.verdictReason
                )
                return SkipHit(result, matchRule)
            }
        }
        return null
    }

    /** 执行单条匹配规则；返回命中的选择器（包名/Activity 仅用于判据日志定位，不参与匹配） */
    private fun applyMatchRule(
        rule: SkipRule,
        group: RuleGroup,
        matchRule: MatchRule,
        root: AccessibilityNodeInfo,
        now: Long,
        enterTimeMs: Long,
        extensionGrantedMs: Long,
        packageName: String,
        currentClassName: String,
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
        // 点击前判据闸门（默认拒绝）：只有拿到"这是开屏广告"的证据才允许点，见 SplashCandidateVerdict
        val evidence = splashGateEvidence(target, matchRule, root, now, enterTimeMs, packageName, currentClassName)
        if (evidence === REJECTED) {
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
        // 证据随命中一起返回，最终落进 AD_SKIPPED / AD_SKIP_UNCONFIRMED 的 detail（可追溯性）
        lastEvidence = evidence as? SplashGate.SplashEvidence
        return target.selector
    }

    /**
     * 点击前判据：采集命中节点的上下文，交给 [SplashCandidateVerdict] 判定。
     *
     * 包名与 Activity 一并传下去 —— 拒绝/放行日志必须能唯一定位到"哪个 App 的哪个页面"，
     * 否则排查时只能靠时间戳对齐隔壁「进入 App」日志。
     *
     * @return [PASSED_WITHOUT_GATE]（判据关闭）/ [REJECTED]（拒绝，已写调试日志）/ 证据对象（放行）
     */
    private fun splashGateEvidence(
        target: RuleNodeFinder.Target,
        matchRule: MatchRule,
        root: AccessibilityNodeInfo,
        now: Long,
        enterTimeMs: Long,
        packageName: String,
        currentClassName: String,
    ): Any {
        if (!splashGateEnabled) return PASSED_WITHOUT_GATE
        val candidate = SplashAdContextProbe.probe(
            node = target.node,
            root = root,
            elapsedSinceEnterMs = now - enterTimeMs
        )
        val context = SplashGate.ScreenContext(packageName, currentClassName)
        return SplashGate.check(
            candidate, matchRule.name, target.selector, context, splashGraceMs, splashTextOnlyGraceMs
        ) ?: REJECTED
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

    private companion object {
        /** 判据关闭时的放行标记（区别于"有证据"） */
        val PASSED_WITHOUT_GATE = Any()

        /** 判据拒绝标记（null 会被 ?: 吞掉，因此用独立哨兵） */
        val REJECTED = Any()
    }
}
