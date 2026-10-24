package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleEngine.kt 的计数/冷却/延迟闸门；职责：单条匹配规则「能不能执行」的判定与执行记录
// （actionMaximum / actionCd / matchDelay / actionDelay / preKeys），状态按 ruleId#groupKey#ruleKey 归档。

import com.bsfeng.monitor.domain.model.MatchRule
import com.bsfeng.monitor.domain.model.RuleGroup
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.util.DebugConfig

/**
 * 规则执行闸门。
 *
 * 键空间说明：`ruleId#groupKey#ruleKey`；[MatchRule.actionMaximumKey] 与 [MatchRule.actionCdKey]
 * 可让多条规则共享同一计数/冷却槽（GKD 语义），因此键以集合形式返回。
 */
internal class SkipRuleGate {

    /** 规则计数（key = ruleId#groupKey#ruleKey）→ 已执行次数 */
    private val counters = mutableMapOf<String, Int>()

    /** 规则冷却（key 同上）→ 允许执行的时间点 */
    private val cooldowns = mutableMapOf<String, Long>()

    /** matchDelay 到期时间（key 同上）：到期前该规则不参与执行 */
    private val pendingDelays = mutableMapOf<String, Long>()

    /** actionDelay 确认时间（key 同上）：到期且**再次命中**才真正执行动作（GKD `actionDelay`） */
    private val actionConfirmDelays = mutableMapOf<String, Long>()

    /** 各规则组最近一次执行的规则 key（map key = ruleId#groupKey），用于 `preKeys` 顺序闸门 */
    private val lastExecuted = mutableMapOf<String, Int>()

    /** 清空全部闸门状态（进入新 App / 手动重置时调用） */
    fun clear() {
        counters.clear()
        cooldowns.clear()
        pendingDelays.clear()
        actionConfirmDelays.clear()
        lastExecuted.clear()
    }

    /**
     * 规则是否可执行：匹配窗口、执行次数（含 [MatchRule.actionMaximumKey] 共享）、
     * 冷却（含 [MatchRule.actionCdKey] 共享）、[MatchRule.preKeys] 顺序闸门。
     *
     * @param enterTimeMs 本次进入 App 的时刻
     * @param extensionGrantedMs 本窗口已顺延的额度（`matchTime` 的宽限）
     */
    fun allowed(
        ruleId: String,
        group: RuleGroup,
        matchRule: MatchRule,
        now: Long,
        enterTimeMs: Long,
        extensionGrantedMs: Long,
    ): Boolean {
        val window = matchRule.matchTime ?: group.matchTime
        // 弱网顺延联动：闸门按「本窗口已顺延的额度」一起放宽，额度上限 = 步长 × 次数（默认 +4 秒）。
        // 理由见 SkipRuleEngine 类 KDoc「与 matchTime 的关系」：内置规则的 matchTime 就等于监控窗口，
        // 若只延长扫描而不放宽闸门，顺延期间扫到广告也不允许执行（增强失效）。
        if (window > 0 && now - enterTimeMs > window + extensionGrantedMs) return false
        val limit = matchRule.actionMaximum ?: group.actionMaximum
        if (limit > 0) {
            val used = counterKeys(ruleId, group.key, matchRule).maxOf { counters[it] ?: 0 }
            if (used >= limit) return false
        }
        val cooldown = matchRule.actionCd ?: group.actionCd
        if (cooldown > 0) {
            val until = cooldownKeys(ruleId, group.key, matchRule).maxOf { cooldowns[it] ?: 0L }
            if (now < until) return false
        }
        if (matchRule.preKeys.isNotEmpty()) {
            val last = lastExecuted[groupScopeKey(ruleId, group.key)]
            if (last == null || last !in matchRule.preKeys) return false
        }
        return true
    }

    /** 记录一次执行：写入计数、冷却与 preKeys 游标 */
    fun record(ruleId: String, group: RuleGroup, matchRule: MatchRule, now: Long) {
        counterKeys(ruleId, group.key, matchRule).forEach { counters[it] = (counters[it] ?: 0) + 1 }
        val cooldown = matchRule.actionCd ?: group.actionCd
        if (cooldown > 0) {
            cooldownKeys(ruleId, group.key, matchRule).forEach { cooldowns[it] = now + cooldown }
        }
        lastExecuted[groupScopeKey(ruleId, group.key)] = matchRule.key
    }

    /**
     * actionDelay 语义：命中后先等待，**到期且仍然命中**才执行动作（GKD `actionDelay`，抗误触）。
     * 与 matchDelay 一样以实现为时间戳，不会被后续扫描取消。
     */
    fun actionDelaySatisfied(matchRule: MatchRule, key: String, now: Long): Boolean {
        if (matchRule.actionDelay <= 0) return true
        val confirmAt = actionConfirmDelays[key]
        if (confirmAt == null) {
            actionConfirmDelays[key] = now + matchRule.actionDelay
            DebugConfig.log("SkipEngine: 命中后等待 actionDelay=" + matchRule.actionDelay + "ms 复核 " + key)
            return false
        }
        if (now < confirmAt) return false
        actionConfirmDelays.remove(key)
        return true
    }

    /**
     * matchDelay 语义：首次命中后挂起 [MatchRule.matchDelay]，到期后重新命中才执行。
     * 用"到期时间戳"实现，避免被后续扫描取消（旧实现在 CONTENT_CHANGED 风暴下永不执行）。
     */
    fun delaySatisfied(matchRule: MatchRule, key: String, now: Long): Boolean {
        if (matchRule.matchDelay <= 0) return true
        val readyAt = pendingDelays[key]
        if (readyAt == null) {
            pendingDelays[key] = now + matchRule.matchDelay
            DebugConfig.log("SkipEngine: 规则挂起等待 matchDelay=" + matchRule.matchDelay + "ms " + key)
            return false
        }
        return now >= readyAt
    }

    /** Activity 变化时，重置 [com.bsfeng.monitor.domain.model.ResetMatch.ACTIVITY] 的作用域计数 */
    fun resetActivityScoped(rules: List<SkipRule>) {
        val keys = rules.flatMap { rule ->
            rule.groups.filter { it.resetMatch == com.bsfeng.monitor.domain.model.ResetMatch.ACTIVITY }.flatMap { group ->
                group.rules.map { rule.id + "#" + group.key + "#" + it.key }
            }
        }
        keys.forEach {
            counters.remove(it)
            pendingDelays.remove(it)
            actionConfirmDelays.remove(it)
        }
    }

    /** 单条规则的键（ruleId#groupKey#ruleKey） */
    fun keyOf(ruleId: String, groupKey: Int, key: Int): String =
        groupScopeKey(ruleId, groupKey) + "#" + key

    private fun groupScopeKey(ruleId: String, groupKey: Int): String = ruleId + "#" + groupKey

    /** 计数键：自身 + [MatchRule.actionMaximumKey] 共享键 */
    private fun counterKeys(ruleId: String, groupKey: Int, matchRule: MatchRule): List<String> {
        val keys = mutableListOf(keyOf(ruleId, groupKey, matchRule.key))
        matchRule.actionMaximumKey?.let { keys.add(keyOf(ruleId, groupKey, it)) }
        return keys.distinct()
    }

    /** 冷却键：自身 + [MatchRule.actionCdKey] 共享键 */
    private fun cooldownKeys(ruleId: String, groupKey: Int, matchRule: MatchRule): List<String> {
        val keys = mutableListOf(keyOf(ruleId, groupKey, matchRule.key))
        matchRule.actionCdKey?.let { keys.add(keyOf(ruleId, groupKey, it)) }
        return keys.distinct()
    }
}
